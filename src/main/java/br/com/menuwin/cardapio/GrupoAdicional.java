package br.com.menuwin.cardapio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ex.: "Escolha o pão" (min 1, max 1) ou "Adicionais" (min 0, max 5). */
@Entity
@Table(name = "grupos_adicionais")
@Getter
@Setter
public class GrupoAdicional {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    @Column(nullable = false, length = 80)
    private String nome;

    private int minimo;
    private int maximo = 1;

    @OneToMany(mappedBy = "grupo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordem")
    private List<OpcaoAdicional> opcoes = new ArrayList<>();

    public boolean obrigatorio() {
        return minimo > 0;
    }
}
