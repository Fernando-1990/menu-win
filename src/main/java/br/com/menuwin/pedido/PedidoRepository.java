package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.StatusPagamento;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
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

    /**
     * Histórico do painel: pedidos do período, mais recentes primeiro. Filtrado pela loja (@TenantId).
     * soPendente: pagamento ainda não recebido, em pedido que não foi cancelado.
     */
    @Query(value = """
            select p from Pedido p
            where p.criadoEm >= :inicio and p.criadoEm < :fim
              and p.status in :status
              and (:soPendente = false or (p.status <> :cancelado and exists (
                     select 1 from Pagamento pg where pg.pedido = p and pg.status = :pendente)))
            order by p.criadoEm desc""",
            countQuery = """
            select count(p) from Pedido p
            where p.criadoEm >= :inicio and p.criadoEm < :fim
              and p.status in :status
              and (:soPendente = false or (p.status <> :cancelado and exists (
                     select 1 from Pagamento pg where pg.pedido = p and pg.status = :pendente)))""")
    Page<Pedido> buscarHistorico(Instant inicio, Instant fim, Collection<StatusPedido> status, boolean soPendente,
                                 StatusPedido cancelado, StatusPagamento pendente, Pageable pagina);

    /** Linhas para o resumo do período: [status do pedido, total, status do pagamento, valor do pagamento]. */
    @Query("""
            select p.status, p.total, pg.status, pg.valor from Pedido p
            left join Pagamento pg on pg.pedido = p
            where p.criadoEm >= :inicio and p.criadoEm < :fim""")
    List<Object[]> linhasDoPeriodo(Instant inicio, Instant fim);
}
