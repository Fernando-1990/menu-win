package br.com.menuwin.pagamento;

import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoStatusResponse;
import br.com.menuwin.pedido.PedidoPublicoService;
import br.com.menuwin.pedido.PedidoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Só existe com menuwin.pix.simulado=true (e o SimuladorPixGateway só sobe em dev/test).
 * Faz o papel do banco + Mercado Pago: muda o status da cobrança e dispara o MESMO processamento do
 * webhook real (PixService.processarNotificacao), com todas as conferências de valor, loja e idempotência.
 * O slug no caminho faz o TenantFilter resolver a loja, igual ao webhook.
 */
@RestController
@ConditionalOnProperty(name = "menuwin.pix.simulado", havingValue = "true")
@RequiredArgsConstructor
public class SimuladorPixController {

    private final SimuladorPixGateway simulador;
    private final PixService pixService;
    private final PedidoRepository pedidoRepository;
    private final PagamentoRepository pagamentoRepository;
    private final PedidoPublicoService pedidoPublicoService;
    private final TransactionTemplate tx;

    /** status: APROVADO (cliente pagou), EXPIRADO (não pagou no prazo) ou RECUSADO. */
    @PostMapping("/api/public/lojas/{slug}/simulador-pix/{pedidoId}")
    public PedidoStatusResponse simular(@PathVariable String slug, @PathVariable UUID pedidoId,
                                        @RequestParam StatusPagamento status) {
        if (status == StatusPagamento.PENDENTE || status == StatusPagamento.ESTORNADO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Simule APROVADO, EXPIRADO ou RECUSADO");
        }
        String gatewayId = tx.execute(s -> {
            // findById passa pelo filtro da loja: pedido de outra loja = 404
            pedidoRepository.findById(pedidoId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
            Pagamento pg = pagamentoRepository.findFirstByPedidoIdOrderByCriadoEmDesc(pedidoId).orElseThrow();
            if (pg.getMetodo() != MetodoPagamento.PIX || pg.getGatewayId() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Este pedido não tem cobrança Pix");
            }
            return pg.getGatewayId();
        });
        simulador.definirStatus(gatewayId, status);
        pixService.processarNotificacao(gatewayId);   // o mesmo caminho do webhook real
        return pedidoPublicoService.consultar(pedidoId);
    }
}
