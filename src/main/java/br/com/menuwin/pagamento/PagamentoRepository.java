package br.com.menuwin.pagamento;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pagamento não tem loja_id: sempre chegar nele pelo pedido (que é filtrado por loja). */
public interface PagamentoRepository extends JpaRepository<Pagamento, UUID> {

    Optional<Pagamento> findFirstByPedidoIdOrderByCriadoEmDesc(UUID pedidoId);

    /** Lista da cozinha: pagamentos de vários pedidos numa consulta só. */
    List<Pagamento> findByPedidoIdIn(Collection<UUID> pedidoIds);

    /** Webhook: trava pagamento e pedido até o fim da transação (notificações simultâneas não se atropelam). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pagamento p join fetch p.pedido where p.id = :id")
    Optional<Pagamento> travarComPedido(UUID id);
}
