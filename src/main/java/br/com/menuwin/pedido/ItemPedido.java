package br.com.menuwin.pedido;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guarda uma "foto" do produto no momento da compra (nome e preço).
 * produtoId é só referência: mudar ou apagar o produto não altera o histórico.
 */
@Entity
@Table(name = "itens_pedido")
@Getter
@Setter
public class ItemPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    private UUID produtoId;

    @Column(nullable = false, length = 120)
    private String nomeProduto;

    @Column(nullable = false)
    private BigDecimal precoUnitario;

    private int quantidade = 1;

    private String observacao;

    @Column(nullable = false)
    private BigDecimal totalItem = BigDecimal.ZERO;

    @OneToMany(mappedBy = "itemPedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemPedidoAdicional> adicionais = new ArrayList<>();

    public void adicionarAdicional(ItemPedidoAdicional adicional) {
        adicional.setItemPedido(this);
        adicionais.add(adicional);
    }

    /** (preço unitário + adicionais de uma unidade) × quantidade. */
    public BigDecimal calcularTotal() {
        BigDecimal somaAdicionais = adicionais.stream()
                .map(a -> a.getPreco().multiply(BigDecimal.valueOf(a.getQuantidade())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.totalItem = precoUnitario.add(somaAdicionais).multiply(BigDecimal.valueOf(quantidade));
        return totalItem;
    }
}
