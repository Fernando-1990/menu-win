package br.com.menuwin.cardapio;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "produtos")
@Getter
@Setter
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID lojaId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @Column(nullable = false, length = 120)
    private String nome;

    private String descricao;
    private String fotoUrl;

    @Column(nullable = false)
    private BigDecimal preco;

    private BigDecimal precoPromocional;

    private int ordem;
    private boolean disponivel = true;   // "acabou hoje"
    private boolean ativo = true;        // soft delete

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;

    @UpdateTimestamp
    private Instant atualizadoEm;

    /** Ordem dos grupos no produto vem da coluna produto_grupos_adicionais.ordem. */
    @ManyToMany
    @JoinTable(name = "produto_grupos_adicionais",
            joinColumns = @JoinColumn(name = "produto_id"),
            inverseJoinColumns = @JoinColumn(name = "grupo_id"))
    @OrderColumn(name = "ordem")
    private List<GrupoAdicional> grupos = new ArrayList<>();

    /** Preço que o cliente paga agora. */
    public BigDecimal precoVigente() {
        return precoPromocional != null ? precoPromocional : preco;
    }
}
