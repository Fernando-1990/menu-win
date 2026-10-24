package br.com.menuwin.cardapio;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GrupoAdicionalRepository extends JpaRepository<GrupoAdicional, UUID> {

    @EntityGraph(attributePaths = "opcoes")
    List<GrupoAdicional> findAllByOrderByNomeAsc();
}
