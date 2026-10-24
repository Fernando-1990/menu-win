package br.com.menuwin.loja;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface LojaRepository extends JpaRepository<Loja, UUID> {

    @Query("select l.id from Loja l where l.slug = :slug and l.ativa = true")
    Optional<UUID> findIdAtivaBySlug(String slug);

    /**
     * Reserva o próximo número curto de pedido da loja.
     * O UPDATE trava a linha da loja até o fim da transação: pedidos simultâneos não repetem número,
     * e se a transação falhar o número volta. Query nativa: o filtro de loja é o próprio WHERE id.
     */
    @Query(value = """
            UPDATE lojas SET proximo_numero_pedido = proximo_numero_pedido + 1
            WHERE id = :lojaId
            RETURNING proximo_numero_pedido - 1""", nativeQuery = true)
    int reservarNumeroPedido(UUID lojaId);
}
