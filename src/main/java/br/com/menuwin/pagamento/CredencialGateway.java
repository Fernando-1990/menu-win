package br.com.menuwin.pagamento;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Token da loja no gateway, sempre cifrado (ver Cifrador). Nunca sai pela API. */
@Entity
@Table(name = "credenciais_gateway")
@Getter
@Setter
public class CredencialGateway {

    public static final String MERCADO_PAGO = "MERCADO_PAGO";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    @Column(nullable = false, length = 30)
    private String gateway;

    @Column(nullable = false)
    private String accessTokenCifrado;

    /** Token de sandbox (TEST-...): cobranças não movimentam dinheiro de verdade. */
    private boolean modoTeste;

    @UpdateTimestamp
    private Instant atualizadoEm;
}
