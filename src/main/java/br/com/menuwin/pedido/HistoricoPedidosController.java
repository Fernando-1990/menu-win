package br.com.menuwin.pedido;

import br.com.menuwin.pedido.HistoricoDtos.HistoricoResponse;
import br.com.menuwin.pedido.HistoricoDtos.PedidoDetalhe;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Histórico do painel. Só DONO e GERENTE (SecurityConfig): mostra faturamento.
 * Datas no formato 2026-10-24, interpretadas no fuso da loja; sem datas = hoje.
 */
@RestController
@RequestMapping("/api/painel/historico/pedidos")
@RequiredArgsConstructor
public class HistoricoPedidosController {

    private final HistoricoPedidosService service;

    @GetMapping
    public HistoricoResponse listar(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
                                    @RequestParam(required = false) List<StatusPedido> status,
                                    @RequestParam(defaultValue = "false") boolean pagamentoPendente,
                                    @RequestParam(defaultValue = "0") int pagina) {
        return service.listar(de, ate, status, pagamentoPendente, pagina);
    }

    @GetMapping("/{id}")
    public PedidoDetalhe detalhar(@PathVariable UUID id) {
        return service.detalhar(id);
    }
}
