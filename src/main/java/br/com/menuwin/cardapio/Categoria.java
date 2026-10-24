package br.com.menuwin.cardapio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

@Entity
@Table(name = "categorias")
@Getter
@Setter
public class Categoria {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    @Column(nullable = false, length = 80)
    private String nome;

    private int ordem;
    private boolean ativa = true;
}
