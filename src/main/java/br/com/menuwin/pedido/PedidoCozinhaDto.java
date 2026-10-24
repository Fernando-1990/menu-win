package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.Pagamento;
import br.com.menuwin.pagamento.StatusPagamento;
import br.com.menuwin.shared.Endereco;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Um pedido como a cozinha/balcão vê: na lista inicial e em cada evento SSE (o frontend troca pelo id).
 * pagamentoPendenteNoBalcao: pago em mãos e ainda não recebido. Destacar, principalmente se já ENTREGUE.
 */
public record PedidoCozinhaDto(UUID id, int numero, StatusPedido status, TipoEntrega tipoEntrega, Instant criadoEm,
                               String clienteNome, String clienteTelefone, Endereco endereco, String observacao,
                               BigDecimal total, MetodoPagamento metodoPagamento, StatusPagamento statusPagamento,
                               BigDecimal trocoPara, boolean pagamentoPendenteNoBalcao, String motivoCancelamento,
                               List<Item> itens) {

    /** adicionais já formatados para a tela: "Pão: Australiano", "Extras: 2x Bacon". */
    public record Item(String nome, int quantidade, String observacao, List<String> adicionais) {}

    static PedidoCozinhaDto de(Pedido p, Pagamento pg) {
        List<Item> itens = p.getItens().stream()
                .map(i -> new Item(i.getNomeProduto(), i.getQuantidade(), i.getObservacao(),
                        i.getAdicionais().stream()
                                .map(a -> a.getNomeGrupo() + ": " + (a.getQuantidade() > 1 ? a.getQuantidade() + "x " : "") + a.getNomeOpcao())
                                .toList()))
                .toList();
        boolean pendenteNoBalcao = pg != null && pg.getMetodo().recebidoPelaLoja() && pg.getStatus() == StatusPagamento.PENDENTE;
        return new PedidoCozinhaDto(p.getId(), p.getNumero(), p.getStatus(), p.getTipoEntrega(), p.getCriadoEm(),
                p.getCliente() != null ? p.getCliente().getNome() : null,
                p.getCliente() != null ? p.getCliente().getTelefone() : null,
                p.getEnderecoEntrega(), p.getObservacao(), p.getTotal(),
                pg != null ? pg.getMetodo() : null, pg != null ? pg.getStatus() : null,
                pg != null ? pg.getTrocoPara() : null, pendenteNoBalcao, p.getMotivoCancelamento(), itens);
    }
}
