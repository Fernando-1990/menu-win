package br.com.menuwin.cardapio;

import br.com.menuwin.cardapio.CardapioResponse.CategoriaDto;
import br.com.menuwin.cardapio.CardapioResponse.GrupoDto;
import br.com.menuwin.cardapio.CardapioResponse.LojaInfo;
import br.com.menuwin.cardapio.CardapioResponse.OpcaoDto;
import br.com.menuwin.cardapio.CardapioResponse.ProdutoDto;
import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.pagamento.CredenciaisService;
import br.com.menuwin.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CardapioPublicoService {

    private final LojaRepository lojaRepository;
    private final CategoriaRepository categoriaRepository;
    private final ProdutoRepository produtoRepository;
    private final GrupoAdicionalRepository grupoRepository;
    private final CredenciaisService credenciais;

    @Transactional(readOnly = true)
    public CardapioResponse carregar() {
        Loja loja = lojaRepository.findById(TenantContext.getObrigatorio()).orElseThrow();

        // As consultas abaixo já vêm filtradas pela loja atual (@TenantId).
        // Grupos (com opções) primeiro: os produtos reaproveitam as mesmas instâncias, sem consulta por grupo.
        grupoRepository.findAllByOrderByNomeAsc();
        List<Categoria> categorias = categoriaRepository.findByAtivaTrueOrderByOrdemAsc();

        Map<UUID, List<ProdutoDto>> produtosPorCategoria = produtoRepository.findByAtivoTrueOrderByOrdemAsc()
                .stream()
                .filter(p -> !loja.isMostrarSoComFoto() || p.getFotoUrl() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getCategoria().getId(),   // ler o id do proxy não dispara consulta
                        Collectors.mapping(this::toDto, Collectors.toList())));

        List<CategoriaDto> resultado = categorias.stream()
                .map(c -> new CategoriaDto(c.getId(), c.getNome(), produtosPorCategoria.getOrDefault(c.getId(), List.of())))
                .filter(c -> !c.produtos().isEmpty())
                .toList();

        return new CardapioResponse(
                new LojaInfo(loja.getNome(), loja.getLogoUrl(), loja.getCapaUrl(), loja.getCorPrimaria(), loja.getLayout(),
                        loja.estaAbertaAgora(), loja.isAceitaRetirada(), loja.isAceitaDelivery(), loja.isAceitaConsumoLocal(),
                        credenciais.aceitaPix(), credenciais.pixSimulado(), loja.isAceitaPagarNoCaixa(),
                        loja.getTaxaEntregaPadrao(), loja.getPedidoMinimo()),
                resultado);
    }

    private ProdutoDto toDto(Produto p) {
        List<GrupoDto> grupos = p.getGrupos().stream().map(this::toDto).toList();
        boolean podeSerPedido = grupos.stream().allMatch(g -> g.opcoes().size() >= g.minimo());
        return new ProdutoDto(p.getId(), p.getNome(), p.getDescricao(), p.getFotoUrl(),
                p.getPreco(), p.getPrecoPromocional(), p.isDisponivel() && podeSerPedido, grupos);
    }

    private GrupoDto toDto(GrupoAdicional g) {
        List<OpcaoDto> opcoes = g.getOpcoes().stream()
                .filter(OpcaoAdicional::isDisponivel)
                .map(o -> new OpcaoDto(o.getId(), o.getNome(), o.getPreco()))
                .toList();
        return new GrupoDto(g.getId(), g.getNome(), g.getMinimo(), g.getMaximo(), opcoes);
    }
}
