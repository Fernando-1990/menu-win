package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.StatusPagamento;
import br.com.menuwin.shared.Endereco;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Histórico de pedidos do painel (DONO/GERENTE). */
public final class HistoricoDtos {

    private HistoricoDtos() {}

    /** Uma página da lista + o resumo do período inteiro (não depende dos filtros de situação). */
    public record HistoricoResponse(LocalDate de, LocalDate ate, ResumoPeriodo resumo,
                                    List<PedidoResumo> pedidos, int pagina, long totalFiltrado, boolean temMais) {}

    /**
     * vendido: pedidos não cancelados (e fora de "aguardando Pix").
     * recebido: pagamentos aprovados. aReceber: pagamentos pendentes de pedidos não cancelados.
     */
    public record ResumoPeriodo(long pedidos, BigDecimal vendido, BigDecimal recebido, BigDecimal aReceber,
                                long cancelados) {}

    public record PedidoResumo(UUID id, int numero, Instant criadoEm, StatusPedido status, TipoEntrega tipoEntrega,
                               String clienteNome, BigDecimal total, MetodoPagamento metodoPagamento,
                               StatusPagamento statusPagamento) {}

    public record PedidoDetalhe(UUID id, int numero, Instant criadoEm, StatusPedido status, TipoEntrega tipoEntrega,
                                OrigemPedido origem, String clienteNome, String clienteTelefone, Endereco endereco,
                                String observacao, String motivoCancelamento,
                                BigDecimal subtotal, BigDecimal taxaEntrega, BigDecimal desconto, BigDecimal total,
                                List<PedidoCozinhaDto.Item> itens, Pagamento pagamento, List<Etapa> linhaDoTempo) {}

    public record Pagamento(MetodoPagamento metodo, StatusPagamento status, BigDecimal valor, BigDecimal trocoPara,
                            Instant pagoEm, boolean recebidoPelaLoja) {}

    /** quem: nome do usuário do painel, ou null quando foi o sistema/cliente (pedido, webhook do Pix). */
    public record Etapa(StatusPedido status, Instant quando, String quem) {}
}
