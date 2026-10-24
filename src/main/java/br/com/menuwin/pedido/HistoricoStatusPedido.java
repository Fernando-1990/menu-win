package br.com.menuwin.pedido;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "historico_status_pedido")
@Getter
@Setter
public class HistoricoStatusPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id")
    private Pedido pedido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPedido status;

    /** null = mudança feita pelo sistema ou pelo cliente. */
    private UUID usuarioId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;
}
