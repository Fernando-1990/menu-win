package br.com.menuwin.pagamento;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static br.com.menuwin.loja.Loja.FUSO;

/**
 * API de pagamentos do Mercado Pago (v1). Docs: https://www.mercadopago.com.br/developers/pt/reference
 * Cada chamada usa o access token da loja.
 */
@Component
public class MercadoPagoGateway implements GatewayPagamento {

    /** Formato que o MP aceita em date_of_expiration. */
    private static final DateTimeFormatter DATA_MP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final RestClient http;

    public MercadoPagoGateway(RestClient.Builder builder, @Value("${menuwin.mercadopago.api-url}") String apiUrl) {
        this.http = builder.baseUrl(apiUrl).build();
    }

    @Override
    public String nome() {
        return CredencialGateway.MERCADO_PAGO;
    }

    @Override
    public void validarCredencial(String accessToken) {
        executar(() -> http.get().uri("/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().toBodilessEntity());
    }

    @Override
    public CobrancaPix criarPix(String accessToken, NovaCobrancaPix c) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("transaction_amount", c.valor());
        corpo.put("description", c.descricao());
        corpo.put("payment_method_id", "pix");
        corpo.put("external_reference", c.referencia().toString());
        corpo.put("date_of_expiration", DATA_MP.format(c.expiraEm().atZone(FUSO)));
        corpo.put("payer", Map.of("email", c.emailPagador(), "first_name", c.nomePagador()));
        if (c.urlNotificacao() != null) {
            corpo.put("notification_url", c.urlNotificacao());
        }

        PagamentoMp mp = executar(() -> http.post().uri("/v1/payments")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Idempotency-Key", c.referencia().toString())   // retry não gera cobrança duplicada
                .contentType(MediaType.APPLICATION_JSON)
                .body(corpo)
                .retrieve().body(PagamentoMp.class));

        if (mp == null || mp.pointOfInteraction() == null || mp.pointOfInteraction().transactionData() == null) {
            throw new GatewayException("Mercado Pago não devolveu os dados do Pix", false, null);
        }
        DadosTransacao pix = mp.pointOfInteraction().transactionData();
        Instant expira = mp.dateOfExpiration() != null ? mp.dateOfExpiration().toInstant() : c.expiraEm();
        return new CobrancaPix(String.valueOf(mp.id()), pix.qrCode(), pix.qrCodeBase64(), expira);
    }

    @Override
    public SituacaoPagamento consultar(String accessToken, String gatewayId) {
        PagamentoMp mp = executar(() -> http.get().uri("/v1/payments/{id}", gatewayId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().body(PagamentoMp.class));
        if (mp == null) {
            throw new GatewayException("Resposta vazia do Mercado Pago", false, null);
        }
        return new SituacaoPagamento(String.valueOf(mp.id()), traduzir(mp.status()), mp.externalReference(),
                mp.transactionAmount());
    }

    /** Status do MP → nosso. Os intermediários (pending, in_process, authorized...) viram PENDENTE. */
    static StatusPagamento traduzir(String status) {
        if (status == null) return StatusPagamento.PENDENTE;
        return switch (status) {
            case "approved" -> StatusPagamento.APROVADO;
            case "rejected" -> StatusPagamento.RECUSADO;
            case "cancelled" -> StatusPagamento.EXPIRADO;   // Pix não pago no prazo é cancelado pelo MP
            case "refunded", "charged_back" -> StatusPagamento.ESTORNADO;
            default -> StatusPagamento.PENDENTE;
        };
    }

    private static <T> T executar(java.util.function.Supplier<T> chamada) {
        try {
            return chamada.get();
        } catch (RestClientResponseException e) {
            boolean credencialInvalida = e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                    || e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN);
            throw new GatewayException("Mercado Pago respondeu " + e.getStatusCode().value(), credencialInvalida, e);
        } catch (RestClientException e) {
            throw new GatewayException("Falha ao chamar o Mercado Pago", false, e);
        }
    }

    // Só os campos que usamos da resposta do MP.

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PagamentoMp(long id, String status, String externalReference, BigDecimal transactionAmount,
                       OffsetDateTime dateOfExpiration, PontoDeInteracao pointOfInteraction) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PontoDeInteracao(DadosTransacao transactionData) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DadosTransacao(String qrCode, String qrCodeBase64, String ticketUrl) {}
}
