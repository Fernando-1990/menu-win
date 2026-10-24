package br.com.menuwin.cardapio;

import br.com.menuwin.cardapio.CardapioPainelDtos.ProdutoDto;
import br.com.menuwin.cardapio.CardapioPainelDtos.ProdutoRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProdutoPainelService {

    private final ProdutoRepository produtos;
    private final CategoriaRepository categorias;
    private final GrupoAdicionalRepository grupos;

    @Transactional(readOnly = true)
    public List<ProdutoDto> listar(UUID categoriaId) {
        List<Produto> lista = categoriaId == null
                ? produtos.findAllByAtivoTrueOrderByOrdemAscNomeAsc()
                : produtos.findAllByAtivoTrueAndCategoriaIdOrderByOrdemAscNomeAsc(categoriaId);
        return lista.stream().map(ProdutoDto::de).toList();
    }

    @Transactional(readOnly = true)
    public ProdutoDto buscar(UUID id) {
        return ProdutoDto.de(carregar(id));
    }

    @Transactional
    public ProdutoDto criar(ProdutoRequest req) {
        Produto p = new Produto();
        aplicar(p, req);
        return ProdutoDto.de(produtos.save(p));
    }

    @Transactional
    public ProdutoDto atualizar(UUID id, ProdutoRequest req) {
        Produto p = carregar(id);
        aplicar(p, req);
        return ProdutoDto.de(p);
    }

    /** "Acabou hoje": também liberado para a cozinha. */
    @Transactional
    public ProdutoDto alterarDisponibilidade(UUID id, boolean disponivel) {
        Produto p = carregar(id);
        p.setDisponivel(disponivel);
        return ProdutoDto.de(p);
    }

    /** Soft delete: pedidos antigos continuam referenciando o produto. */
    @Transactional
    public void remover(UUID id) {
        carregar(id).setAtivo(false);
    }

    private Produto carregar(UUID id) {
        return produtos.findById(id)
                .filter(Produto::isAtivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
    }

    private void aplicar(Produto p, ProdutoRequest req) {
        if (req.precoPromocional() != null && req.precoPromocional().compareTo(req.preco()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O preço promocional deve ser menor que o preço");
        }
        // Buscar pelo repositório garante que a categoria é da mesma loja (filtro @TenantId).
        p.setCategoria(categorias.findById(req.categoriaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria não encontrada")));
        p.setNome(req.nome().trim());
        p.setDescricao(req.descricao());
        p.setFotoUrl(req.fotoUrl());
        p.setPreco(req.preco());
        p.setPrecoPromocional(req.precoPromocional());
        p.setOrdem(req.ordem());
        if (req.disponivel() != null) {
            p.setDisponivel(req.disponivel());
        }
        p.getGrupos().clear();
        p.getGrupos().addAll(resolverGrupos(req.grupoIds()));
    }

    /** Mantém a ordem pedida; grupo de outra loja não aparece no findAllById e cai no erro. */
    private List<GrupoAdicional> resolverGrupos(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grupo de adicionais repetido no produto");
        }
        Map<UUID, GrupoAdicional> porId = grupos.findAllById(ids).stream()
                .collect(Collectors.toMap(GrupoAdicional::getId, Function.identity()));
        return ids.stream()
                .map(id -> {
                    GrupoAdicional g = porId.get(id);
                    if (g == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grupo de adicionais não encontrado: " + id);
                    }
                    return g;
                })
                .toList();
    }
}
