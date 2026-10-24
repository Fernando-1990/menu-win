package br.com.menuwin.pedido;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "itens_pedido_adicionais")
@Getter
@Setter
public class ItemPedidoAdicional {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_pedido_id")
    private ItemPedido itemPedido;

    private UUID opcaoId;

    @Column(nullable = false, length = 80)
    private String nomeGrupo;

    @Column(nullable = false, length = 80)
    private String nomeOpcao;

    @Column(nullable = false)
    private BigDecimal preco;

    private int quantidade = 1;
}
