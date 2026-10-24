package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.StatusPagamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PedidoRepository extends JpaRepository<Pedido, UUID> {

    /**
     * Tela da cozinha/balcão: pedidos em andamento + entregues que ainda não foram pagos em mãos.
     * Filtrado pela loja do token (@TenantId).
     */
    @Query("""
            select p from Pedido p
            where p.status in :ativos
               or (p.status = :entregue and exists (
                     select 1 from Pagamento pg
                     where pg.pedido = p and pg.status = :pendente and pg.metodo in :recebidosPelaLoja))
            order by p.criadoEm""")
    List<Pedido> findParaCozinha(Collection<StatusPedido> ativos, StatusPedido entregue,
                                 StatusPagamento pendente, Collection<MetodoPagamento> recebidosPelaLoja);
}
