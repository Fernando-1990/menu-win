package br.com.menuwin.pagamento;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fala com o gateway usando o token da PRÓPRIA loja (o dinheiro cai na conta dela).
 * Hoje só Mercado Pago; outro gateway = outra implementação, sem mexer no fluxo do pedido.
 */
public interface GatewayPagamento {

    String nome();

    /** Lança GatewayException se o token for inválido. */
    void validarCredencial(String accessToken);

    CobrancaPix criarPix(String accessToken, NovaCobrancaPix cobranca);

    SituacaoPagamento consultar(String accessToken, String gatewayId);

    /** referencia = id do nosso Pagamento: volta no webhook e também serve de chave de idempotência. */
    record NovaCobrancaPix(UUID referencia, BigDecimal valor, String descricao, String emailPagador,
                           String nomePagador, Instant expiraEm, String urlNotificacao) {}

    record CobrancaPix(String gatewayId, String copiaECola, String qrCodeBase64, Instant expiraEm) {}

    /** status PENDENTE = nada mudou para nós (pending, in_process...). referencia pode ser nula. */
    record SituacaoPagamento(String gatewayId, StatusPagamento status, String referencia, BigDecimal valor) {}

    class GatewayException extends RuntimeException {
        private final boolean credencialInvalida;

        public GatewayException(String mensagem, boolean credencialInvalida, Throwable causa) {
            super(mensagem, causa);
            this.credencialInvalida = credencialInvalida;
        }

        public boolean isCredencialInvalida() {
            return credencialInvalida;
        }
    }
}
