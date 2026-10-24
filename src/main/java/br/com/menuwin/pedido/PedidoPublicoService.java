package br.com.menuwin.pedido;

import br.com.menuwin.cardapio.GrupoAdicional;
import br.com.menuwin.cardapio.OpcaoAdicional;
import br.com.menuwin.cardapio.Produto;
import br.com.menuwin.cardapio.ProdutoRepository;
import br.com.menuwin.cliente.Cliente;
import br.com.menuwin.cliente.ClienteRepository;
import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.pagamento.CredenciaisService;
import br.com.menuwin.pagamento.CredencialGateway;
import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.Pagamento;
import br.com.menuwin.pagamento.PagamentoRepository;
import br.com.menuwin.pagamento.StatusPagamento;
import br.com.menuwin.pedido.PedidoPublicoDtos.AdicionalRequest;
import br.com.menuwin.pedido.PedidoPublicoDtos.AdicionalResumo;
import br.com.menuwin.pedido.PedidoPublicoDtos.ClienteRequest;
import br.com.menuwin.pedido.PedidoPublicoDtos.CriarPedidoRequest;
import br.com.menuwin.pedido.PedidoPublicoDtos.ItemRequest;
import br.com.menuwin.pedido.PedidoPublicoDtos.ItemResumo;
import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoCriadoResponse;
import br.com.menuwin.pedido.PedidoPublicoDtos.PedidoStatusResponse;
import br.com.menuwin.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Criação de pedido pelo cardápio público. A loja vem do slug (TenantFilter) e todas as
 * consultas de cardápio são filtradas por ela (@TenantId): produto de outra loja simplesmente não é encontrado.
 */
@Service
@RequiredArgsConstructor
public class PedidoPublicoService {

    private static final Set<MetodoPagamento> NA_ENTREGA =
            Set.of(MetodoPagamento.DINHEIRO_NA_ENTREGA, MetodoPagamento.CARTAO_NA_ENTREGA);

    private final CredenciaisService credenciais;
    private final LojaRepository lojaRepository;
    private final ProdutoRepository produtoRepository;
    private final ClienteRepository clienteRepository;
    private final PedidoRepository pedidoRepository;
    private final PagamentoRepository pagamentoRepository;
    private final NotificadorCozinha notificador;

    @Transactional
    public PedidoCriadoResponse criar(CriarPedidoRequest req) {
        Loja loja = lojaRepository.findById(TenantContext.getObrigatorio()).orElseThrow();
        validarLoja(loja, req);

        Pedido pedido = new Pedido();
        pedido.setOrigem(OrigemPedido.CARDAPIO);
        pedido.setTipoEntrega(req.tipoEntrega());
        pedido.setObservacao(req.observacao());
        if (req.tipoEntrega() == TipoEntrega.DELIVERY) {
            pedido.setEnderecoEntrega(req.endereco().paraEndereco());
            pedido.setTaxaEntrega(loja.getTaxaEntregaPadrao());
        }
        montarItens(req.itens()).forEach(pedido::adicionarItem);   // recalcula subtotal e total

        validarValores(loja, pedido, req);

        boolean pix = req.metodoPagamento() == MetodoPagamento.PIX;
        pedido.setCliente(clienteDoPedido(loja, req.cliente()));
        // Pix só vai para a cozinha depois que o webhook confirmar; na entrega ou no caixa, vai direto.
        pedido.mudarStatus(pix ? StatusPedido.AGUARDANDO_PAGAMENTO : StatusPedido.RECEBIDO, null);
        pedido.setNumero(lojaRepository.reservarNumeroPedido(loja.getId()));   // por último: trava a linha da loja
        pedidoRepository.save(pedido);

        Pagamento pagamento = new Pagamento();
        pagamento.setPedido(pedido);
        pagamento.setMetodo(req.metodoPagamento());
        pagamento.setValor(pedido.getTotal());
        pagamento.setTrocoPara(req.trocoPara());
        if (pix) {
            pagamento.setGateway(CredencialGateway.MERCADO_PAGO);   // gateway_id vem depois, fora desta transação
        }
        pagamentoRepository.save(pagamento);

        notificador.pedidoMudou(pedido);   // tela da cozinha recebe depois do commit
        return resposta(pedido, req.metodoPagamento());
    }

    /** Acompanhamento pelo cliente (tela do Pix faz polling aqui). O id é um UUID: não dá para adivinhar. */
    @Transactional(readOnly = true)
    public PedidoStatusResponse consultar(UUID id) {
        Pedido p = pedidoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
        Pagamento pg = pagamentoRepository.findFirstByPedidoIdOrderByCriadoEmDesc(p.getId()).orElseThrow();
        boolean pixPendente = pg.getMetodo() == MetodoPagamento.PIX && pg.getStatus() == StatusPagamento.PENDENTE;
        return new PedidoStatusResponse(p.getId(), p.getNumero(), p.getStatus(), p.getTotal(), pg.getMetodo(),
                pg.getStatus(), pixPendente ? pg.getPixCopiaCola() : null, pixPendente ? pg.getPixExpiraEm() : null,
                p.getMotivoCancelamento());
    }

    // ---------------------------------------------------------------- loja e entrega

    private void validarLoja(Loja loja, CriarPedidoRequest req) {
        if (!loja.estaAbertaAgora()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A loja está fechada agora");
        }
        // Mesma ordem da tela: primeiro como o cliente vai receber, depois como vai pagar.
        switch (req.tipoEntrega()) {
            case RETIRADA -> {
                if (!loja.isAceitaRetirada()) throw erro("Esta loja não aceita retirada");
            }
            case DELIVERY -> {
                if (!loja.isAceitaDelivery()) throw erro("Esta loja não faz entrega");
                if (req.endereco() == null) throw erro("Informe o endereço de entrega");
            }
            case CONSUMO_LOCAL -> {
                if (!loja.isAceitaConsumoLocal()) throw erro("Esta loja não atende consumo no local pelo cardápio");
            }
        }
        if (req.metodoPagamento() == MetodoPagamento.PIX) {
            if (!credenciais.aceitaPix()) throw erro("Esta loja não aceita Pix pelo cardápio");
            // o Mercado Pago exige e-mail do pagador no Pix
            if (req.cliente().email() == null || req.cliente().email().isBlank()) throw erro("Informe seu e-mail para pagar com Pix");
        } else if (req.metodoPagamento() == MetodoPagamento.NO_CAIXA) {
            if (!loja.isAceitaPagarNoCaixa()) throw erro("Esta loja não aceita pagar no caixa");
            if (req.tipoEntrega() == TipoEntrega.DELIVERY) throw erro("Pagar no caixa vale só para retirada ou consumo no local");
        } else if (!NA_ENTREGA.contains(req.metodoPagamento())) {
            throw erro("Cartão online ainda não disponível. Escolha outra forma de pagamento.");
        }
    }

    private static void validarValores(Loja loja, Pedido pedido, CriarPedidoRequest req) {
        if (pedido.getSubtotal().compareTo(loja.getPedidoMinimo()) < 0) {
            throw erro("O pedido mínimo desta loja é R$ " + loja.getPedidoMinimo());
        }
        if (pedido.getTotal().signum() <= 0) {
            throw erro("O pedido não tem valor a pagar");
        }
        if (req.totalEsperado() != null && req.totalEsperado().compareTo(pedido.getTotal()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O cardápio mudou enquanto você montava o pedido. Total atualizado: R$ " + pedido.getTotal());
        }
        if (req.trocoPara() != null) {
            if (req.metodoPagamento() != MetodoPagamento.DINHEIRO_NA_ENTREGA) {
                throw erro("Troco só vale para pagamento em dinheiro");
            }
            if (req.trocoPara().compareTo(pedido.getTotal()) < 0) {
                throw erro("O valor para troco é menor que o total do pedido");
            }
        }
    }

    // ---------------------------------------------------------------- itens e adicionais

    private List<ItemPedido> montarItens(List<ItemRequest> itens) {
        Set<UUID> ids = itens.stream().map(ItemRequest::produtoId).collect(Collectors.toSet());
        Map<UUID, Produto> produtos = produtoRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Produto::getId, Function.identity()));
        return itens.stream().map(i -> montarItem(i, produtos.get(i.produtoId()))).toList();
    }

    private static ItemPedido montarItem(ItemRequest req, Produto produto) {
        if (produto == null || !produto.isAtivo() || !produto.getCategoria().isAtiva()) {
            throw erro("Produto não encontrado no cardápio");
        }
        if (!produto.isDisponivel()) {
            throw erro(produto.getNome() + " acabou por hoje");
        }

        ItemPedido item = new ItemPedido();
        item.setProdutoId(produto.getId());
        item.setNomeProduto(produto.getNome());          // snapshot
        item.setPrecoUnitario(produto.precoVigente());   // snapshot, já com promoção
        item.setQuantidade(req.quantidadeOuUm());
        item.setObservacao(req.observacao());
        adicionais(produto, req.adicionaisOuVazio()).forEach(item::adicionarAdicional);
        return item;
    }

    /** Cada opção precisa ser de um grupo do produto; por grupo, a soma das quantidades fica entre mínimo e máximo. */
    private static List<ItemPedidoAdicional> adicionais(Produto produto, List<AdicionalRequest> pedidos) {
        Map<UUID, OpcaoAdicional> opcoesDoProduto = new HashMap<>();
        produto.getGrupos().forEach(g -> g.getOpcoes().forEach(o -> opcoesDoProduto.put(o.getId(), o)));

        Set<UUID> vistas = new HashSet<>();
        Map<GrupoAdicional, Integer> quantidadePorGrupo = new HashMap<>();
        List<ItemPedidoAdicional> resultado = pedidos.stream().map(req -> {
            OpcaoAdicional opcao = opcoesDoProduto.get(req.opcaoId());
            if (opcao == null) {
                throw erro(produto.getNome() + ": adicional não pertence a este produto");
            }
            if (!vistas.add(opcao.getId())) {
                throw erro(produto.getNome() + ": adicional repetido; use a quantidade");
            }
            if (!opcao.isDisponivel()) {
                throw erro(produto.getNome() + ": " + opcao.getNome() + " acabou por hoje");
            }
            quantidadePorGrupo.merge(opcao.getGrupo(), req.quantidadeOuUm(), Integer::sum);

            ItemPedidoAdicional a = new ItemPedidoAdicional();
            a.setOpcaoId(opcao.getId());
            a.setNomeGrupo(opcao.getGrupo().getNome());   // snapshot
            a.setNomeOpcao(opcao.getNome());              // snapshot
            a.setPreco(opcao.getPreco());                 // snapshot
            a.setQuantidade(req.quantidadeOuUm());
            return a;
        }).toList();

        for (GrupoAdicional g : produto.getGrupos()) {
            int escolhidas = quantidadePorGrupo.getOrDefault(g, 0);
            if (escolhidas < g.getMinimo()) {
                throw erro(produto.getNome() + ": escolha pelo menos " + g.getMinimo() + " em \"" + g.getNome() + "\"");
            }
            if (escolhidas > g.getMaximo()) {
                throw erro(produto.getNome() + ": no máximo " + g.getMaximo() + " em \"" + g.getNome() + "\"");
            }
        }
        return resultado;
    }

    // ---------------------------------------------------------------- cliente

    /** Mesmo telefone na mesma loja = mesmo cliente; nome e e-mail ficam com os dados mais recentes. */
    private Cliente clienteDoPedido(Loja loja, ClienteRequest req) {
        String telefone = Cliente.normalizarTelefone(req.telefone())
                .orElseThrow(() -> erro("Telefone inválido. Use DDD + número."));
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim().toLowerCase();
        UUID id = clienteRepository.salvarPorTelefone(loja.getId(), req.nome().trim(), telefone, email);
        return clienteRepository.getReferenceById(id);
    }

    // ---------------------------------------------------------------- resposta

    private static PedidoCriadoResponse resposta(Pedido p, MetodoPagamento metodo) {
        List<ItemResumo> itens = p.getItens().stream()
                .map(i -> new ItemResumo(i.getNomeProduto(), i.getQuantidade(), i.getPrecoUnitario(), i.getTotalItem(),
                        i.getObservacao(),
                        i.getAdicionais().stream()
                                .map(a -> new AdicionalResumo(a.getNomeGrupo(), a.getNomeOpcao(), a.getQuantidade(), a.getPreco()))
                                .toList()))
                .toList();
        return new PedidoCriadoResponse(p.getId(), p.getNumero(), p.getStatus(), p.getTipoEntrega(), metodo,
                p.getSubtotal(), p.getTaxaEntrega(), p.getDesconto(), p.getTotal(), itens, null);
    }

    private static ResponseStatusException erro(String mensagem) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
