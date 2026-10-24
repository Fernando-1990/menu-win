package br.com.menuwin.cardapio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

// Repare: nenhum método recebe lojaId. O filtro por loja é automático (@TenantId).
public interface CategoriaRepository extends JpaRepository<Categoria, UUID> {
    List<Categoria> findByAtivaTrueOrderByOrdemAsc();

    /** Painel: inclui as inativas, para poder reativar. */
    List<Categoria> findAllByOrderByOrdemAscNomeAsc();
}
