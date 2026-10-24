package br.com.menuwin.cardapio;

import br.com.menuwin.cardapio.CardapioPainelDtos.CategoriaDto;
import br.com.menuwin.cardapio.CardapioPainelDtos.CategoriaRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Todas as consultas já vêm filtradas pela loja do token (@TenantId): id de outra loja = 404. */
@Service
@RequiredArgsConstructor
public class CategoriaPainelService {

    private final CategoriaRepository categorias;
    private final ProdutoRepository produtos;

    @Transactional(readOnly = true)
    public List<CategoriaDto> listar() {
        return categorias.findAllByOrderByOrdemAscNomeAsc().stream().map(CategoriaDto::de).toList();
    }

    @Transactional(readOnly = true)
    public CategoriaDto buscar(UUID id) {
        return CategoriaDto.de(carregar(id));
    }

    @Transactional
    public CategoriaDto criar(CategoriaRequest req) {
        Categoria c = new Categoria();
        aplicar(c, req);
        return CategoriaDto.de(categorias.save(c));
    }

    @Transactional
    public CategoriaDto atualizar(UUID id, CategoriaRequest req) {
        Categoria c = carregar(id);
        aplicar(c, req);
        return CategoriaDto.de(c);
    }

    /**
     * Não apaga de verdade: produtos inativos (soft delete) continuam apontando para a categoria.
     * Com produto ativo dentro, recusa: o lojista precisa mover ou remover os produtos antes.
     */
    @Transactional
    public void remover(UUID id) {
        Categoria c = carregar(id);
        if (produtos.existsByCategoriaIdAndAtivoTrue(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A categoria tem produtos. Mova ou remova os produtos antes.");
        }
        c.setAtiva(false);
    }

    private Categoria carregar(UUID id) {
        return categorias.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoria não encontrada"));
    }

    private static void aplicar(Categoria c, CategoriaRequest req) {
        c.setNome(req.nome().trim());
        c.setOrdem(req.ordem());
        if (req.ativa() != null) {
            c.setAtiva(req.ativa());
        }
    }
}
