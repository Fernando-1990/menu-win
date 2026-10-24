package br.com.menuwin.pedido;

import br.com.menuwin.tenant.TenantContext;
import br.com.menuwin.usuario.Papel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * Tela da cozinha/balcão. Todos os papéis do painel acessam; cancelar exige DONO/GERENTE (no serviço).
 *
 * SSE e autenticação: o EventSource nativo do navegador NÃO envia o header Authorization.
 * O frontend deve usar SSE via fetch (ex.: @microsoft/fetch-event-source). Token na URL fica proibido
 * (vaza em log de proxy e no histórico).
 */
@RestController
@RequestMapping("/api/painel/pedidos")
@RequiredArgsConstructor
public class PedidoCozinhaController {

    private final PedidoCozinhaService service;
    private final CentralEventosCozinha central;

    public record MudarStatusRequest(@NotNull StatusPedido status, @Size(max = 300) String motivo) {}

    @GetMapping("/abertos")
    public List<PedidoCozinhaDto> abertos() {
        return service.abertos();
    }

    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos() {
        return central.conectar(TenantContext.getObrigatorio());
    }

    @PatchMapping("/{id}/status")
    public PedidoCozinhaDto mudarStatus(@PathVariable UUID id, @Valid @RequestBody MudarStatusRequest req,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.mudarStatus(id, req.status(), req.motivo(), UUID.fromString(jwt.getSubject()),
                Papel.valueOf(jwt.getClaimAsString("papel")));
    }

    @PostMapping("/{id}/pagamento/recebido")
    public PedidoCozinhaDto pagamentoRecebido(@PathVariable UUID id) {
        return service.registrarPagamentoRecebido(id);
    }
}
