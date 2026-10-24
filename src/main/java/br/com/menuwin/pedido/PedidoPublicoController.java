package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.PixService;
import br.com.menuwin.pagamento.PixService.DadosPix;
import br.com.menuwin.pedido.PedidoPublicoDtos.CriarPedidoRequest;
import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoCriadoResponse;
import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoStatusResponse;
import br.com.menuwin.pedido.PedidoPublicoDtos.PixDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** O slug já foi resolvido para a loja pelo TenantFilter. */
@RestController
@RequestMapping("/api/public/lojas/{slug}")
@RequiredArgsConstructor
public class PedidoPublicoController {

    private final PedidoPublicoService service;
    private final PixService pixService;

    /**
     * Duas etapas de propósito: o pedido é gravado (e commitado) primeiro; a cobrança Pix
     * é gerada depois, fora da transação (ver PixService).
     */
    @PostMapping("/pedidos")
    @ResponseStatus(HttpStatus.CREATED)
    public PedidoCriadoResponse criar(@PathVariable String slug, @Valid @RequestBody CriarPedidoRequest req) {
        PedidoCriadoResponse pedido = service.criar(req);
        if (pedido.metodoPagamento() != MetodoPagamento.PIX) {
            return pedido;
        }
        DadosPix pix = pixService.gerarCobranca(pedido.id());
        return pedido.comPix(new PixDto(pix.copiaECola(), pix.qrCodeBase64(), pix.expiraEm()));
    }

    @GetMapping("/pedidos/{id}")
    public PedidoStatusResponse consultar(@PathVariable String slug, @PathVariable UUID id) {
        return service.consultar(id);
    }
}
