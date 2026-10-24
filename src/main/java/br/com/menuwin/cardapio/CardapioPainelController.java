package br.com.menuwin.cardapio;

import br.com.menuwin.cardapio.CardapioPainelDtos.CategoriaDto;
import br.com.menuwin.cardapio.CardapioPainelDtos.CategoriaRequest;
import br.com.menuwin.cardapio.CardapioPainelDtos.DisponibilidadeRequest;
import br.com.menuwin.cardapio.CardapioPainelDtos.GrupoDto;
import br.com.menuwin.cardapio.CardapioPainelDtos.GrupoRequest;
import br.com.menuwin.cardapio.CardapioPainelDtos.ProdutoDto;
import br.com.menuwin.cardapio.CardapioPainelDtos.ProdutoRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CRUD do cardápio da loja do token.
 * Permissões (SecurityConfig): DONO e GERENTE em tudo; COZINHA só na disponibilidade do produto.
 */
@RestController
@RequestMapping("/api/painel/cardapio")
@RequiredArgsConstructor
public class CardapioPainelController {

    private final CategoriaPainelService categorias;
    private final ProdutoPainelService produtos;
    private final GrupoAdicionalPainelService grupos;

    // ---- categorias ----

    @GetMapping("/categorias")
    public List<CategoriaDto> listarCategorias() {
        return categorias.listar();
    }

    @GetMapping("/categorias/{id}")
    public CategoriaDto buscarCategoria(@PathVariable UUID id) {
        return categorias.buscar(id);
    }

    @PostMapping("/categorias")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoriaDto criarCategoria(@Valid @RequestBody CategoriaRequest req) {
        return categorias.criar(req);
    }

    @PutMapping("/categorias/{id}")
    public CategoriaDto atualizarCategoria(@PathVariable UUID id, @Valid @RequestBody CategoriaRequest req) {
        return categorias.atualizar(id, req);
    }

    @DeleteMapping("/categorias/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removerCategoria(@PathVariable UUID id) {
        categorias.remover(id);
    }

    // ---- produtos ----

    @GetMapping("/produtos")
    public List<ProdutoDto> listarProdutos(@RequestParam(required = false) UUID categoriaId) {
        return produtos.listar(categoriaId);
    }

    @GetMapping("/produtos/{id}")
    public ProdutoDto buscarProduto(@PathVariable UUID id) {
        return produtos.buscar(id);
    }

    @PostMapping("/produtos")
    @ResponseStatus(HttpStatus.CREATED)
    public ProdutoDto criarProduto(@Valid @RequestBody ProdutoRequest req) {
        return produtos.criar(req);
    }

    @PutMapping("/produtos/{id}")
    public ProdutoDto atualizarProduto(@PathVariable UUID id, @Valid @RequestBody ProdutoRequest req) {
        return produtos.atualizar(id, req);
    }

    @PatchMapping("/produtos/{id}/disponibilidade")
    public ProdutoDto alterarDisponibilidade(@PathVariable UUID id, @Valid @RequestBody DisponibilidadeRequest req) {
        return produtos.alterarDisponibilidade(id, req.disponivel());
    }

    @DeleteMapping("/produtos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removerProduto(@PathVariable UUID id) {
        produtos.remover(id);
    }

    // ---- grupos de adicionais (com as opções) ----

    @GetMapping("/grupos")
    public List<GrupoDto> listarGrupos() {
        return grupos.listar();
    }

    @GetMapping("/grupos/{id}")
    public GrupoDto buscarGrupo(@PathVariable UUID id) {
        return grupos.buscar(id);
    }

    @PostMapping("/grupos")
    @ResponseStatus(HttpStatus.CREATED)
    public GrupoDto criarGrupo(@Valid @RequestBody GrupoRequest req) {
        return grupos.criar(req);
    }

    @PutMapping("/grupos/{id}")
    public GrupoDto atualizarGrupo(@PathVariable UUID id, @Valid @RequestBody GrupoRequest req) {
        return grupos.atualizar(id, req);
    }

    @DeleteMapping("/grupos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removerGrupo(@PathVariable UUID id) {
        grupos.remover(id);
    }
}
