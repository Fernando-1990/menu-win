package br.com.menuwin.pedido;

import br.com.menuwin.cliente.Cliente;
import br.com.menuwin.shared.Endereco;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pedidos")
@Getter
@Setter
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    /** Número curto exibido ao cliente e na cozinha (#0128). */
    @Column(nullable = false, updatable = false)
    private Integer numero;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrigemPedido origem = OrigemPedido.CARDAPIO;

    /** Id do pedido na plataforma de origem (iFood). */
    private String idExterno;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoEntrega tipoEntrega;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)   // use mudarStatus
    private StatusPedido status = StatusPedido.RECEBIDO;

    @JdbcTypeCode(SqlTypes.JSON)
    private Endereco enderecoEntrega;

    private String observacao;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal taxaEntrega = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal desconto = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    private String motivoCancelamento;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;

    @UpdateTimestamp
    private Instant atualizadoEm;

    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemPedido> itens = new ArrayList<>();

    /** Só cresce: cada mudança de status vira uma linha (ver mudarStatus). */
    @OneToMany(mappedBy = "pedido", cascade = CascadeType.PERSIST)
    @Setter(AccessLevel.NONE)
    private List<HistoricoStatusPedido> historico = new ArrayList<>();

    /**
     * Único jeito de mudar o status: grava junto a linha do histórico.
     * usuarioId nulo = sistema ou cliente (webhook, criação pelo cardápio).
     */
    public void mudarStatus(StatusPedido novo, UUID usuarioId) {
        this.status = novo;
        HistoricoStatusPedido h = new HistoricoStatusPedido();
        h.setPedido(this);
        h.setStatus(novo);
        h.setUsuarioId(usuarioId);
        historico.add(h);
    }

    public void adicionarItem(ItemPedido item) {
        item.setPedido(this);
        itens.add(item);
        recalcularTotais();
    }

    /** Mantém a regra do banco: total = subtotal + taxa_entrega - desconto. */
    public void recalcularTotais() {
        this.subtotal = itens.stream()
                .map(ItemPedido::calcularTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.total = subtotal.add(taxaEntrega).subtract(desconto);
    }
}
