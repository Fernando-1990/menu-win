package br.com.menuwin.pedido;

import br.com.menuwin.loja.Loja;
import br.com.menuwin.pagamento.Pagamento;
import br.com.menuwin.pagamento.PagamentoRepository;
import br.com.menuwin.pagamento.StatusPagamento;
import br.com.menuwin.pedido.HistoricoDtos.Etapa;
import br.com.menuwin.pedido.HistoricoDtos.HistoricoResponse;
import br.com.menuwin.pedido.HistoricoDtos.PedidoDetalhe;
import br.com.menuwin.pedido.HistoricoDtos.PedidoResumo;
import br.com.menuwin.pedido.HistoricoDtos.ResumoPeriodo;
import br.com.menuwin.tenant.TenantContext;
import br.com.menuwin.usuario.Usuario;
import br.com.menuwin.usuario.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pedidos de qualquer situação, por período (dias no fuso da loja). Tudo filtrado pela loja do token. */
@Service
@RequiredArgsConstructor
public class HistoricoPedidosService {

    static final int TAMANHO_PAGINA = 30;
    static final int MAX_DIAS = 366;

    private final PedidoRepository pedidoRepository;
    private final PagamentoRepository pagamentoRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(readOnly = true)
    public HistoricoResponse listar(LocalDate de, LocalDate ate, List<StatusPedido> status, boolean soPendente, int pagina) {
        LocalDate hoje = LocalDate.now(Loja.FUSO);
        LocalDate inicioDia = de != null ? de : hoje;
        LocalDate fimDia = ate != null ? ate : hoje;
        if (fimDia.isBefore(inicioDia)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data final é antes da inicial");
        }
        if (ChronoUnit.DAYS.between(inicioDia, fimDia) >= MAX_DIAS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha um período de até 1 ano");
        }
        Instant inicio = inicioDia.atStartOfDay(Loja.FUSO).toInstant();
        Instant fim = fimDia.plusDays(1).atStartOfDay(Loja.FUSO).toInstant();   // até o fim do último dia
        Collection<StatusPedido> situacoes = status == null || status.isEmpty() ? Arrays.asList(StatusPedido.values()) : status;

        Page<Pedido> pedidos = pedidoRepository.buscarHistorico(inicio, fim, situacoes, soPendente,
                StatusPedido.CANCELADO, StatusPagamento.PENDENTE, PageRequest.of(Math.max(pagina, 0), TAMANHO_PAGINA));
        Map<UUID, Pagamento> pagamentos = maisRecentePorPedido(pedidos.stream().map(Pedido::getId).toList());

        List<PedidoResumo> lista = pedidos.stream().map(p -> {
            Pagamento pg = pagamentos.get(p.getId());
            return new PedidoResumo(p.getId(), p.getNumero(), p.getCriadoEm(), p.getStatus(), p.getTipoEntrega(),
                    p.getNomeCliente(), p.getTotal(), pg != null ? pg.getMetodo() : null, pg != null ? pg.getStatus() : null);
        }).toList();

        return new HistoricoResponse(inicioDia, fimDia, resumir(pedidoRepository.linhasDoPeriodo(inicio, fim)),
                lista, pedidos.getNumber(), pedidos.getTotalElements(), pedidos.hasNext());
    }

    @Transactional(readOnly = true)
    public PedidoDetalhe detalhar(UUID id) {
        Pedido p = pedidoRepository.findById(id)   // filtro da loja: pedido de outra loja = 404
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        Pagamento pg = pagamentoRepository.findFirstByPedidoIdOrderByCriadoEmDesc(p.getId()).orElse(null);

        // Usuario não tem @TenantId: confere a loja na mão (o histórico só tem usuários desta loja, mas por garantia).
        UUID loja = TenantContext.getObrigatorio();
        Map<UUID, String> nomes = usuarioRepository.findAllById(p.getHistorico().stream()
                        .map(HistoricoStatusPedido::getUsuarioId).filter(Objects::nonNull).distinct().toList())
                .stream().filter(u -> u.getLojaId().equals(loja))
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNome));
        List<Etapa> linhaDoTempo = p.getHistorico().stream()
                .map(h -> new Etapa(h.getStatus(), h.getCriadoEm(), h.getUsuarioId() == null ? null : nomes.get(h.getUsuarioId())))
                .toList();

        return new PedidoDetalhe(p.getId(), p.getNumero(), p.getCriadoEm(), p.getStatus(), p.getTipoEntrega(), p.getOrigem(),
                p.getNomeCliente(), p.getTelefoneCliente(), p.getEnderecoEntrega(), p.getObservacao(), p.getMotivoCancelamento(),
                p.getSubtotal(), p.getTaxaEntrega(), p.getDesconto(), p.getTotal(), PedidoCozinhaDto.itensDe(p),
                pg == null ? null : new HistoricoDtos.Pagamento(pg.getMetodo(), pg.getStatus(), pg.getValor(),
                        pg.getTrocoPara(), pg.getPagoEm(), pg.getMetodo().recebidoPelaLoja()),
                linhaDoTempo);
    }

    private Map<UUID, Pagamento> maisRecentePorPedido(List<UUID> pedidoIds) {
        if (pedidoIds.isEmpty()) return Map.of();
        return pagamentoRepository.findByPedidoIdIn(pedidoIds).stream()
                .collect(Collectors.toMap(pg -> pg.getPedido().getId(), Function.identity(),
                        (a, b) -> Comparator.comparing(Pagamento::getCriadoEm).compare(a, b) >= 0 ? a : b));
    }

    /** Linhas: [status do pedido, total, status do pagamento, valor do pagamento]. */
    static ResumoPeriodo resumir(List<Object[]> linhas) {
        long pedidos = linhas.size();
        long cancelados = 0;
        BigDecimal vendido = BigDecimal.ZERO, recebido = BigDecimal.ZERO, aReceber = BigDecimal.ZERO;
        for (Object[] l : linhas) {
            StatusPedido status = (StatusPedido) l[0];
            BigDecimal total = (BigDecimal) l[1];
            StatusPagamento pagamento = (StatusPagamento) l[2];
            BigDecimal valor = l[3] == null ? BigDecimal.ZERO : (BigDecimal) l[3];
            boolean valeComoVenda = status != StatusPedido.CANCELADO && status != StatusPedido.AGUARDANDO_PAGAMENTO;
            if (status == StatusPedido.CANCELADO) cancelados++;
            if (valeComoVenda) vendido = vendido.add(total);
            if (pagamento == StatusPagamento.APROVADO) recebido = recebido.add(valor);
            if (valeComoVenda && pagamento == StatusPagamento.PENDENTE) aReceber = aReceber.add(valor);
        }
        return new ResumoPeriodo(pedidos, vendido, recebido, aReceber, cancelados);
    }
}
