package br.com.menuwin.cardapio;

import br.com.menuwin.loja.Layout;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CardapioResponse(LojaInfo loja, List<CategoriaDto> categorias) {

    /** aberta, entrega e valores: o que o checkout precisa para montar o pedido. */
    public record LojaInfo(String nome, String logoUrl, String capaUrl, String corPrimaria, Layout layout,
                           boolean aberta, boolean aceitaRetirada, boolean aceitaDelivery, boolean aceitaConsumoLocal,
                           boolean aceitaPix, boolean aceitaPagarNoCaixa,
                           BigDecimal taxaEntrega, BigDecimal pedidoMinimo) {}

    public record CategoriaDto(UUID id, String nome, List<ProdutoDto> produtos) {}

    /** disponivel = falso também quando um grupo obrigatório não tem opções disponíveis suficientes. */
    public record ProdutoDto(UUID id, String nome, String descricao, String fotoUrl,
                             BigDecimal preco, BigDecimal precoPromocional, boolean disponivel,
                             List<GrupoDto> grupos) {}

    /** Só as opções disponíveis. Soma das quantidades escolhidas: entre minimo e maximo. */
    public record GrupoDto(UUID id, String nome, int minimo, int maximo, List<OpcaoDto> opcoes) {}

    public record OpcaoDto(UUID id, String nome, BigDecimal preco) {}
}
