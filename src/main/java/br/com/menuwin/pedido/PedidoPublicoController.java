package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.PixService;
import br.com.menuwin.pagamento.PixService.DadosPix;
import br.com.menuwin.pedido.PedidoPublicoDtos.CriarPedidoRequest;
import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoCriadoResponse;
import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoStatusResponse;
import br.com.menuwin.pedido.PedidoPublicoDtos.PixDto;
import br.com.menuwin.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/** O slug já foi resolvido para a loja pelo TenantFilter. */
@RestController
@RequestMapping("/api/public/lojas/{slug}")
@RequiredArgsConstructor
public class PedidoPublicoController {

    private final PedidoPublicoService service;
    private final PixService pixService;
    private final LimitePedidos limite;

    /**
     * Duas etapas de propósito: o pedido é gravado (e commitado) primeiro; a cobrança Pix
     * é gerada depois, fora da transação (ver PixService).
     *
     * Idempotency-Key (UUID, opcional): o navegador gera uma por tentativa de pedido. Reenvio com a mesma
     * chave devolve o pedido já criado (200) em vez de criar outro (201). Reenvio não conta no limite.
     */
    @PostMapping("/pedidos")
    public ResponseEntity<PedidoCriadoResponse> criar(@PathVariable String slug,
                                                      @RequestHeader(name = "Idempotency-Key", required = false) UUID chave,
                                                      @Valid @RequestBody CriarPedidoRequest req,
                                                      HttpServletRequest http) {
        if (chave != null) {
            Optional<PedidoCriadoResponse> jaCriado = service.jaCriado(chave);
            if (jaCriado.isPresent()) return ResponseEntity.ok(jaCriado.get());
        }

        UUID lojaId = TenantContext.getObrigatorio();
        String ip = ipDoCliente(http);
        String telefone = req.cliente().telefone();
        limite.verificar(lojaId, ip, telefone);

        PedidoCriadoResponse pedido;
        try {
            pedido = service.criar(req, chave);
        } catch (DataIntegrityViolationException e) {
            // dois envios com a mesma chave ao mesmo tempo: o outro gravou primeiro
            if (chave == null) throw e;
            return ResponseEntity.ok(service.jaCriado(chave).orElseThrow(() -> e));
        }
        limite.registrar(lojaId, ip, telefone);

        if (pedido.metodoPagamento() == MetodoPagamento.PIX) {
            DadosPix pix = pixService.gerarCobranca(pedido.id());
            pedido = pedido.comPix(new PixDto(pix.copiaECola(), pix.qrCodeBase64(), pix.expiraEm()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(pedido);
    }

    @GetMapping("/pedidos/{id}")
    public PedidoStatusResponse consultar(@PathVariable String slug, @PathVariable UUID id) {
        return service.consultar(id);
    }

    /**
     * IP de quem fez o pedido. No Render há Cloudflare e o proxy do Render na frente; o proxy acrescenta ao
     * X-Forwarded-For o que o cliente mandou (dá para forjar). Por isso o limite por IP é só uma camada:
     * o teto por loja é que não se contorna.
     */
    static String ipDoCliente(HttpServletRequest http) {
        for (String header : new String[] {"CF-Connecting-IP", "True-Client-IP"}) {
            String valor = http.getHeader(header);
            if (valor != null && !valor.isBlank()) return valor.trim();
        }
        return http.getRemoteAddr();   // com forward-headers-strategy=framework, já é o primeiro do X-Forwarded-For
    }
}
