package br.com.menuwin.cliente;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Cliente pertence à loja: cada lojista é dono da própria base. */
@Entity
@Table(name = "clientes")
@Getter
@Setter
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    @Column(nullable = false, length = 120)
    private String nome;

    /** Normalizado, só dígitos com DDI: 5511999999999. */
    @Column(nullable = false, length = 20)
    private String telefone;

    private String email;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;

    /**
     * Deixa só dígitos e garante o DDI 55: "(11) 99999-9999" vira "5511999999999".
     * Devolve vazio se não parecer um telefone brasileiro (10 ou 11 dígitos com DDD).
     */
    public static Optional<String> normalizarTelefone(String bruto) {
        String digitos = bruto == null ? "" : bruto.replaceAll("\\D", "");
        if (digitos.length() == 10 || digitos.length() == 11) {
            return Optional.of("55" + digitos);
        }
        if ((digitos.length() == 12 || digitos.length() == 13) && digitos.startsWith("55")) {
            return Optional.of(digitos);
        }
        return Optional.empty();
    }
}
