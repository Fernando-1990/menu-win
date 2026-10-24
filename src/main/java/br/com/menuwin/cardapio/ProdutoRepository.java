package br.com.menuwin.cardapio;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {
    @EntityGraph(attributePaths = "grupos")
    List<Produto> findByAtivoTrueOrderByOrdemAsc();

    // Painel: já traz os grupos de cada produto (evita uma consulta por produto).
    @EntityGraph(attributePaths = "grupos")
    List<Produto> findAllByAtivoTrueOrderByOrdemAscNomeAsc();

    @EntityGraph(attributePaths = "grupos")
    List<Produto> findAllByAtivoTrueAndCategoriaIdOrderByOrdemAscNomeAsc(UUID categoriaId);

    boolean existsByCategoriaIdAndAtivoTrue(UUID categoriaId);
}
