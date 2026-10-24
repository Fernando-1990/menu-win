package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.Pagamento;
import br.com.menuwin.pagamento.PagamentoRepository;
import br.com.menuwin.pagamento.StatusPagamento;
import br.com.menuwin.usuario.Papel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static br.com.menuwin.pedido.StatusPedido.*;

/**
 * Cozinha e balcão: lista, andamento do pedido e pagamento recebido em mãos.
 * Toda mudança avisa as telas da loja (NotificadorCozinha, depois do commit).
 */
@Service
@RequiredArgsConstructor
public class PedidoCozinhaService {

    /** Em andamento. Entre eles dá para avançar, pular ou voltar (clique errado acontece). */
    static final Set<StatusPedido> ATIVOS = EnumSet.of(RECEBIDO, EM_PREPARO, PRONTO, SAIU_PARA_ENTREGA);
    static final Set<StatusPedido> FINAIS = EnumSet.of(ENTREGUE, CANCELADO);
    private static final List<MetodoPagamento> RECEBIDOS_PELA_LOJA =
            Arrays.stream(MetodoPagamento.values()).filter(MetodoPagamento::recebidoPelaLoja).toList();

    private final PedidoRepository pedidoRepository;
    private final PagamentoRepository pagamentoRepository;
    private final NotificadorCozinha notificador;

    @Transactional(readOnly = true)
    public List<PedidoCozinhaDto> abertos() {
        List<Pedido> pedidos = pedidoRepository.findParaCozinha(ATIVOS, ENTREGUE, StatusPagamento.PENDENTE, RECEBIDOS_PELA_LOJA);
        Map<UUID, Pagamento> pagamentos = pagamentoRepository.findByPedidoIdIn(pedidos.stream().map(Pedido::getId).toList())
                .stream()
                .collect(Collectors.toMap(pg -> pg.getPedido().getId(), Function.identity(),
                        (a, b) -> Comparator.comparing(Pagamento::getCriadoEm).compare(a, b) >= 0 ? a : b));   // o mais recente
        return pedidos.stream().map(p -> PedidoCozinhaDto.de(p, pagamentos.get(p.getId()))).toList();
    }

    /**
     * Regras:
     *  - Entre ATIVOS e para ENTREGUE: livre (avançar, pular, voltar). SAIU_PARA_ENTREGA só em delivery.
     *  - AGUARDANDO_PAGAMENTO não vai para a cozinha na mão: quem libera é o webhook do Pix.
     *  - CANCELADO: só DONO/GERENTE e com motivo.
     *  - ENTREGUE e CANCELADO são finais.
     */
    @Transactional
    public PedidoCozinhaDto mudarStatus(UUID pedidoId, StatusPedido novo, String motivo, UUID usuarioId, Papel papel) {
        Pedido p = carregar(pedidoId);
        if (FINAIS.contains(p.getStatus())) {
            if (p.getStatus() == novo) return notificador.pedidoMudou(p);   // clique repetido
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido já finalizado (" + p.getStatus() + ")");
        }
        if (novo == CANCELADO) {
            if (papel == Papel.COZINHA) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Só o dono ou o gerente cancela pedido");
            }
            if (motivo == null || motivo.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo do cancelamento");
            }
            p.setMotivoCancelamento(motivo.trim());
        } else {
            if (p.getStatus() == AGUARDANDO_PAGAMENTO) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pedido aguardando o Pix: vai para a cozinha sozinho quando o pagamento for confirmado");
            }
            if (!ATIVOS.contains(novo) && novo != ENTREGUE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status inválido para a cozinha: " + novo);
            }
            if (novo == SAIU_PARA_ENTREGA && p.getTipoEntrega() != TipoEntrega.DELIVERY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Só pedido de delivery sai para entrega");
            }
        }
        if (p.getStatus() != novo) {
            p.mudarStatus(novo, usuarioId);
        }
        return notificador.pedidoMudou(p);
    }

    /** Pagamento em mãos (caixa ou entregador). Pix online não passa por aqui: o webhook confirma. Idempotente. */
    @Transactional
    public PedidoCozinhaDto registrarPagamentoRecebido(UUID pedidoId) {
        Pedido p = carregar(pedidoId);
        Pagamento pg = pagamentoRepository.findFirstByPedidoIdOrderByCriadoEmDesc(p.getId()).orElseThrow();
        if (!pg.getMetodo().recebidoPelaLoja()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pagamento online é confirmado automaticamente pelo Mercado Pago");
        }
        if (p.getStatus() == CANCELADO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido cancelado");
        }
        if (pg.getStatus() == StatusPagamento.PENDENTE) {
            pg.setStatus(StatusPagamento.APROVADO);
            pg.setPagoEm(Instant.now());
        }
        return notificador.pedidoMudou(p);
    }

    private Pedido carregar(UUID id) {
        return pedidoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
    }
}
