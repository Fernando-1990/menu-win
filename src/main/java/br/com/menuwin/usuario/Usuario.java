package br.com.menuwin.usuario;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Sem @TenantId de propósito: no login ainda não sabemos a loja,
 * e a busca é pelo e-mail (único no sistema todo). A loja vem DEPOIS, do próprio usuário.
 * Consultas de usuários no painel devem filtrar por lojaId manualmente.
 */
@Entity
@Table(name = "usuarios")
@Getter
@Setter
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    @Column(nullable = false, length = 120)
    private String nome;

    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @Column(nullable = false, length = 100)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Papel papel;

    private boolean ativo = true;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;
}
