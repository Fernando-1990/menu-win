package br.com.menuwin.pagamento;

import br.com.menuwin.pagamento.GatewayPagamento.CobrancaPix;
import br.com.menuwin.pagamento.GatewayPagamento.GatewayException;
import br.com.menuwin.pagamento.GatewayPagamento.NovaCobrancaPix;
import br.com.menuwin.pagamento.GatewayPagamento.SituacaoPagamento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Contrato HTTP com a API do Mercado Pago, sem rede: o servidor é simulado. */
class MercadoPagoGatewayTest {

    MockRestServiceServer mp;
    MercadoPagoGateway gateway;

    @BeforeEach
    void montar() {
        RestClient.Builder builder = RestClient.builder();
        mp = MockRestServiceServer.bindTo(builder).build();
        gateway = new MercadoPagoGateway(builder, "https://mp.teste");
    }

    @Test
    void criarPixMandaOQueOMpExigeELeOQrCode() {
        UUID referencia = UUID.randomUUID();
        mp.expect(requestTo("https://mp.teste/v1/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer TEST-123"))
                .andExpect(header("X-Idempotency-Key", referencia.toString()))
                .andExpect(jsonPath("$.payment_method_id").value("pix"))
                .andExpect(jsonPath("$.transaction_amount").value(52.5))
                .andExpect(jsonPath("$.external_reference").value(referencia.toString()))
                .andExpect(jsonPath("$.date_of_expiration").value("2026-10-24T10:00:00.000-03:00"))
                .andExpect(jsonPath("$.payer.email").value("maria@exemplo.com"))
                .andExpect(jsonPath("$.notification_url").value("https://api/webhook"))
                .andRespond(withSuccess("""
                        {"id": 123456, "status": "pending",
                         "date_of_expiration": "2026-10-24T10:00:00.000-03:00",
                         "point_of_interaction": {"transaction_data": {
                           "qr_code": "00020126...copia", "qr_code_base64": "iVBORw0KGgo", "ticket_url": "https://mp/t"}}}
                        """, MediaType.APPLICATION_JSON));

        CobrancaPix pix = gateway.criarPix("TEST-123", new NovaCobrancaPix(referencia, new BigDecimal("52.50"),
                "Pedido #1", "maria@exemplo.com", "Maria", Instant.parse("2026-10-24T13:00:00Z"), "https://api/webhook"));

        assertThat(pix.gatewayId()).isEqualTo("123456");
        assertThat(pix.copiaECola()).isEqualTo("00020126...copia");
        assertThat(pix.qrCodeBase64()).isEqualTo("iVBORw0KGgo");
        assertThat(pix.expiraEm()).isEqualTo(Instant.parse("2026-10-24T13:00:00Z"));
        mp.verify();
    }

    @Test
    void semUrlPublicaNaoMandaNotificationUrl() {
        mp.expect(requestTo("https://mp.teste/v1/payments"))
                .andExpect(jsonPath("$.notification_url").doesNotExist())
                .andRespond(withSuccess("""
                        {"id": 1, "point_of_interaction": {"transaction_data": {"qr_code": "x"}}}""", MediaType.APPLICATION_JSON));
        gateway.criarPix("TEST-1", new NovaCobrancaPix(UUID.randomUUID(), BigDecimal.TEN, "d", "a@b.com", "A",
                Instant.now(), null));
        mp.verify();
    }

    @Test
    void consultarTraduzStatus() {
        String ref = UUID.randomUUID().toString();
        mp.expect(requestTo("https://mp.teste/v1/payments/987"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer TEST-123"))
                .andRespond(withSuccess("""
                        {"id": 987, "status": "approved", "external_reference": "%s", "transaction_amount": 52.50}
                        """.formatted(ref), MediaType.APPLICATION_JSON));

        SituacaoPagamento s = gateway.consultar("TEST-123", "987");
        assertThat(s.status()).isEqualTo(StatusPagamento.APROVADO);
        assertThat(s.referencia()).isEqualTo(ref);
        assertThat(s.valor()).isEqualByComparingTo("52.50");

        assertThat(MercadoPagoGateway.traduzir("pending")).isEqualTo(StatusPagamento.PENDENTE);
        assertThat(MercadoPagoGateway.traduzir("in_process")).isEqualTo(StatusPagamento.PENDENTE);
        assertThat(MercadoPagoGateway.traduzir("rejected")).isEqualTo(StatusPagamento.RECUSADO);
        assertThat(MercadoPagoGateway.traduzir("cancelled")).isEqualTo(StatusPagamento.EXPIRADO);
        assertThat(MercadoPagoGateway.traduzir("refunded")).isEqualTo(StatusPagamento.ESTORNADO);
    }

    @Test
    void tokenRecusadoViraCredencialInvalida() {
        mp.expect(requestTo("https://mp.teste/users/me")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> gateway.validarCredencial("TEST-ruim"))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.isCredencialInvalida()).isTrue());

        mp.reset();
        mp.expect(requestTo("https://mp.teste/v1/payments/1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> gateway.consultar("TEST-ok", "1"))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.isCredencialInvalida()).isFalse());
    }
}
