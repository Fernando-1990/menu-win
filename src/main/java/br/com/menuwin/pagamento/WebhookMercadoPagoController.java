package br.com.menuwin.pagamento;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Notificações do Mercado Pago. A URL leva o slug da loja: o TenantFilter resolve a loja
 * e o PixService consulta o pagamento com o token DELA.
 *
 * Formatos aceitos:
 *  - Webhook: corpo {"type":"payment","data":{"id":"123"}} (e ?data.id=123&type=payment)
 *  - IPN antigo: ?topic=payment&id=123
 *
 * Responde 200 para o que não interessa (o MP para de reenviar). Erro ao consultar o MP
 * vira 5xx, e o MP tenta de novo mais tarde.
 */
@RestController
@RequiredArgsConstructor
public class WebhookMercadoPagoController {

    private final PixService pixService;

    @PostMapping("/api/public/lojas/{slug}/webhooks/mercadopago")
    public ResponseEntity<Void> notificar(@PathVariable String slug,
                                          @RequestParam(name = "data.id", required = false) String dataId,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String topic,
                                          @RequestParam(required = false) String id,
                                          @RequestBody(required = false) JsonNode corpo) {
        String tipo = primeiro(texto(corpo, "type"), type, topic);
        String pagamentoId = primeiro(corpo != null ? texto(corpo.path("data"), "id") : null, dataId, id);
        if ("payment".equals(tipo) && pagamentoId != null && pagamentoId.matches("\\d{1,20}")) {
            pixService.processarNotificacao(pagamentoId);
        }
        return ResponseEntity.ok().build();
    }

    private static String texto(JsonNode no, String campo) {
        if (no == null || !no.hasNonNull(campo)) return null;
        return no.get(campo).asText();
    }

    private static String primeiro(String... valores) {
        for (String v : valores) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
