package br.com.menuwin.cardapio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "opcoes_adicionais")
@Getter
@Setter
public class OpcaoAdicional {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grupo_id")
    private GrupoAdicional grupo;

    @Column(nullable = false, length = 80)
    private String nome;

    @Column(nullable = false)
    private BigDecimal preco = BigDecimal.ZERO;

    private boolean disponivel = true;
    private int ordem;
}
