package br.com.menuwin.pagamento;

import br.com.menuwin.pedido.Pedido;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dados de cartão NUNCA passam por aqui: ficam no gateway. Guardamos só o id da cobrança. */
@Entity
@Table(name = "pagamentos")
@Getter
@Setter
public class Pagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MetodoPagamento metodo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPagamento status = StatusPagamento.PENDENTE;

    @Column(nullable = false)
    private BigDecimal valor;

    private BigDecimal trocoPara;

    private String gateway;
    private String gatewayId;
    private String pixCopiaCola;
    private Instant pixExpiraEm;
    private Instant pagoEm;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;
}
