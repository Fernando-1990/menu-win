package br.com.menuwin.pagamento;

import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.pagamento.GatewayPagamento.CobrancaPix;
import br.com.menuwin.pagamento.GatewayPagamento.GatewayException;
import br.com.menuwin.pagamento.GatewayPagamento.NovaCobrancaPix;
import br.com.menuwin.pagamento.GatewayPagamento.SituacaoPagamento;
import br.com.menuwin.pedido.NotificadorCozinha;
import br.com.menuwin.pedido.Pedido;
import br.com.menuwin.pedido.PedidoRepository;
import br.com.menuwin.pedido.StatusPedido;
import br.com.menuwin.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cobrança Pix e confirmação via webhook.
 *
 * Chamadas ao gateway ficam FORA de transação: uma chamada HTTP lenta não pode segurar
 * conexão do banco nem a trava da loja (número do pedido). Por isso TransactionTemplate,
 * com transações curtas antes e depois da chamada.
 */
@Slf4j
@Service
public class PixService {

    private final GatewayPagamento gateway;
    private final CredenciaisService credenciais;
    private final PagamentoRepository pagamentoRepository;
    private final PedidoRepository pedidoRepository;
    private final NotificadorCozinha notificador;
    private final LojaRepository lojaRepository;
    private final TransactionTemplate tx;
    private final Duration expiracao;
    private final String urlPublica;

    public PixService(GatewayPagamento gateway, CredenciaisService credenciais, PagamentoRepository pagamentoRepository,
                      PedidoRepository pedidoRepository, NotificadorCozinha notificador,
                      LojaRepository lojaRepository, TransactionTemplate tx,
                      @Value("${menuwin.pix.expiracao}") Duration expiracao,
                      @Value("${menuwin.url-publica:}") String urlPublica) {
        this.gateway = gateway;
        this.credenciais = credenciais;
        this.pagamentoRepository = pagamentoRepository;
        this.pedidoRepository = pedidoRepository;
        this.notificador = notificador;
        this.lojaRepository = lojaRepository;
        this.tx = tx;
        this.expiracao = expiracao;
        this.urlPublica = urlPublica.endsWith("/") ? urlPublica.substring(0, urlPublica.length() - 1) : urlPublica;
    }

    public record DadosPix(String copiaECola, String qrCodeBase64, Instant expiraEm) {}

    private record Preparacao(String token, UUID pagamentoId, NovaCobrancaPix cobranca) {}

    /**
     * Gera o Pix de um pedido recém-criado (já commitado como AGUARDANDO_PAGAMENTO).
     * Se o gateway falhar, cancela o pedido: ele nunca chegou à cozinha, então é seguro.
     */
    public DadosPix gerarCobranca(UUID pedidoId) {
        Preparacao prep = tx.execute(s -> {
            Pedido pedido = pedidoRepository.findById(pedidoId).orElseThrow();
            Pagamento pagamento = pagamentoRepository.findFirstByPedidoIdOrderByCriadoEmDesc(pedidoId).orElseThrow();
            Loja loja = lojaRepository.findById(pedido.getLojaId()).orElseThrow();
            String token = credenciais.tokenMercadoPago()
                    .orElseThrow(() -> new IllegalStateException("Loja sem credencial do Mercado Pago"));
            NovaCobrancaPix cobranca = new NovaCobrancaPix(pagamento.getId(), pagamento.getValor(),
                    "Pedido #" + pedido.getNumero() + " - " + loja.getNome(),
                    pedido.getCliente().getEmail(), pedido.getCliente().getNome(),
                    Instant.now().plus(expiracao), urlNotificacao(loja));
            return new Preparacao(token, pagamento.getId(), cobranca);
        });

        CobrancaPix pix;
        try {
            pix = gateway.criarPix(prep.token(), prep.cobranca());
        } catch (GatewayException e) {
            log.error("Falha ao gerar Pix do pedido {}", pedidoId, e);
            tx.executeWithoutResult(s -> {
                Pagamento pagamento = pagamentoRepository.travarComPedido(prep.pagamentoId()).orElseThrow();
                pagamento.setStatus(StatusPagamento.RECUSADO);
                cancelar(pagamento.getPedido(), "Falha ao gerar o Pix");
                notificador.pedidoMudou(pagamento.getPedido());
            });
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível gerar o Pix agora. Tente de novo ou escolha pagar na entrega.");
        }

        tx.executeWithoutResult(s -> {
            Pagamento pagamento = pagamentoRepository.findById(prep.pagamentoId()).orElseThrow();
            pagamento.setGatewayId(pix.gatewayId());
            pagamento.setPixCopiaCola(pix.copiaECola());
            pagamento.setPixExpiraEm(pix.expiraEm());
        });
        return new DadosPix(pix.copiaECola(), pix.qrCodeBase64(), pix.expiraEm());
    }

    /**
     * Webhook do Mercado Pago. O corpo da notificação NÃO é confiável: só usamos o id para
     * consultar o pagamento na API do MP com o token da loja. Notificação forjada não aprova nada.
     * Idempotente: repetir a mesma notificação não muda nada.
     */
    public void processarNotificacao(String gatewayId) {
        String token = tx.execute(s -> credenciais.tokenMercadoPago().orElse(null));
        if (token == null) {
            log.warn("Webhook do MP para loja {} sem credencial; ignorado", TenantContext.get().orElse(null));
            return;
        }
        SituacaoPagamento situacao = gateway.consultar(token, gatewayId);   // falha aqui = 5xx, o MP tenta de novo
        UUID referencia = paraUuid(situacao.referencia());
        if (referencia == null) {
            log.warn("Pagamento {} do MP sem external_reference nosso; ignorado", gatewayId);
            return;
        }
        tx.executeWithoutResult(s -> aplicar(referencia, situacao));
    }

    private void aplicar(UUID pagamentoId, SituacaoPagamento situacao) {
        Pagamento pagamento = pagamentoRepository.travarComPedido(pagamentoId).orElse(null);
        if (pagamento == null || !pagamento.getPedido().getLojaId().equals(TenantContext.getObrigatorio())) {
            log.warn("Pagamento {} não encontrado nesta loja; ignorado", pagamentoId);
            return;
        }
        if (pagamento.getGatewayId() != null && !pagamento.getGatewayId().equals(situacao.gatewayId())) {
            log.warn("Pagamento {}: id no MP diverge ({} x {}); ignorado", pagamentoId, pagamento.getGatewayId(), situacao.gatewayId());
            return;
        }
        if (situacao.valor() == null || pagamento.getValor().compareTo(situacao.valor()) != 0) {
            log.warn("Pagamento {}: valor diverge (esperado {}, MP {}); ignorado", pagamentoId, pagamento.getValor(), situacao.valor());
            return;
        }
        if (!transicaoPermitida(pagamento.getStatus(), situacao.status())) {
            return;   // repetida ou fora de ordem
        }

        pagamento.setGatewayId(situacao.gatewayId());
        pagamento.setStatus(situacao.status());
        Pedido pedido = pagamento.getPedido();
        switch (situacao.status()) {
            case APROVADO -> {
                pagamento.setPagoEm(Instant.now());
                if (pedido.getStatus() == StatusPedido.AGUARDANDO_PAGAMENTO) {
                    pedido.mudarStatus(StatusPedido.RECEBIDO, null);   // agora vai para a cozinha
                } else {
                    log.warn("Pedido {} pago com status {}: avaliar estorno manual", pedido.getId(), pedido.getStatus());
                }
            }
            case RECUSADO -> cancelarSeAguardando(pedido, "Pagamento recusado");
            case EXPIRADO -> cancelarSeAguardando(pedido, "Pix não pago no prazo");
            default -> { }   // ESTORNADO: só o pagamento muda; o pedido pode já ter sido entregue
        }
        notificador.pedidoMudou(pedido);
    }

    /** De PENDENTE vai para qualquer status final; de APROVADO só para ESTORNADO; o resto é final. */
    private static boolean transicaoPermitida(StatusPagamento atual, StatusPagamento novo) {
        if (novo == StatusPagamento.PENDENTE || novo == atual) return false;
        return atual == StatusPagamento.PENDENTE
                || (atual == StatusPagamento.APROVADO && novo == StatusPagamento.ESTORNADO);
    }

    private void cancelarSeAguardando(Pedido pedido, String motivo) {
        if (pedido.getStatus() == StatusPedido.AGUARDANDO_PAGAMENTO) {
            cancelar(pedido, motivo);
        }
    }

    private static void cancelar(Pedido pedido, String motivo) {
        pedido.setMotivoCancelamento(motivo);
        pedido.mudarStatus(StatusPedido.CANCELADO, null);
    }

    /** O slug no caminho faz o TenantFilter resolver a loja também no webhook. */
    private String urlNotificacao(Loja loja) {
        return urlPublica.isBlank() ? null : urlPublica + "/api/public/lojas/" + loja.getSlug() + "/webhooks/mercadopago";
    }

    private static UUID paraUuid(String texto) {
        try {
            return texto == null ? null : UUID.fromString(texto);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
