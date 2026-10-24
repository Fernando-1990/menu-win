package br.com.menuwin.cardapio;

import br.com.menuwin.cardapio.CardapioPainelDtos.GrupoDto;
import br.com.menuwin.cardapio.CardapioPainelDtos.GrupoRequest;
import br.com.menuwin.cardapio.CardapioPainelDtos.OpcaoRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Opções não têm loja_id: só são alcançadas pelo grupo, que é filtrado por loja.
 * Por isso não existe endpoint de opção avulsa.
 */
@Service
@RequiredArgsConstructor
public class GrupoAdicionalPainelService {

    private final GrupoAdicionalRepository grupos;

    @Transactional(readOnly = true)
    public List<GrupoDto> listar() {
        return grupos.findAllByOrderByNomeAsc().stream().map(GrupoDto::de).toList();
    }

    @Transactional(readOnly = true)
    public GrupoDto buscar(UUID id) {
        return GrupoDto.de(carregar(id));
    }

    @Transactional
    public GrupoDto criar(GrupoRequest req) {
        GrupoAdicional g = new GrupoAdicional();
        aplicar(g, req);
        return GrupoDto.de(grupos.save(g));
    }

    @Transactional
    public GrupoDto atualizar(UUID id, GrupoRequest req) {
        GrupoAdicional g = carregar(id);
        aplicar(g, req);
        grupos.flush();   // DTO sai com os ids das opções novas
        return GrupoDto.de(g);
    }

    /**
     * Apaga de verdade: o banco remove as opções e o vínculo com os produtos (ON DELETE CASCADE).
     * Pedidos antigos ficam com o snapshot e opcao_id nulo (V2).
     */
    @Transactional
    public void remover(UUID id) {
        grupos.delete(carregar(id));
    }

    private GrupoAdicional carregar(UUID id) {
        return grupos.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo de adicionais não encontrado"));
    }

    private static void aplicar(GrupoAdicional g, GrupoRequest req) {
        if (req.minimo() > req.maximo()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O mínimo não pode ser maior que o máximo");
        }
        if (req.minimo() > req.opcoes().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O mínimo é maior que o número de opções: o produto não poderia ser pedido");
        }
        g.setNome(req.nome().trim());
        g.setMinimo(req.minimo());
        g.setMaximo(req.maximo());
        sincronizarOpcoes(g, req.opcoes());
    }

    /** Atualiza as que vieram com id, cria as sem id, remove as que ficaram de fora. Ordem = posição na lista. */
    private static void sincronizarOpcoes(GrupoAdicional g, List<OpcaoRequest> pedidas) {
        List<UUID> idsPedidos = pedidas.stream().map(OpcaoRequest::id).filter(Objects::nonNull).toList();
        Set<UUID> mantidos = new HashSet<>(idsPedidos);
        if (mantidos.size() != idsPedidos.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Opção repetida no grupo");
        }
        Map<UUID, OpcaoAdicional> existentes = g.getOpcoes().stream()
                .collect(Collectors.toMap(OpcaoAdicional::getId, Function.identity()));
        if (!existentes.keySet().containsAll(mantidos)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Opção não pertence a este grupo");
        }

        g.getOpcoes().removeIf(o -> !mantidos.contains(o.getId()));   // orphanRemoval apaga
        for (int i = 0; i < pedidas.size(); i++) {
            OpcaoRequest r = pedidas.get(i);
            OpcaoAdicional o = r.id() == null ? novaOpcao(g) : existentes.get(r.id());
            o.setNome(r.nome().trim());
            o.setPreco(r.preco());
            o.setOrdem(i);
            if (r.disponivel() != null) {
                o.setDisponivel(r.disponivel());
            }
        }
    }

    private static OpcaoAdicional novaOpcao(GrupoAdicional g) {
        OpcaoAdicional o = new OpcaoAdicional();
        o.setGrupo(g);
        g.getOpcoes().add(o);
        return o;
    }
}
