package br.com.menuwin.cardapio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Entrada e saída do CRUD do cardápio no painel. Dinheiro: NUMERIC(10,2). */
public final class CardapioPainelDtos {

    private CardapioPainelDtos() {}

    // ---- categorias ----

    public record CategoriaRequest(@NotBlank @Size(max = 80) String nome, int ordem, Boolean ativa) {}

    public record CategoriaDto(UUID id, String nome, int ordem, boolean ativa) {
        static CategoriaDto de(Categoria c) {
            return new CategoriaDto(c.getId(), c.getNome(), c.getOrdem(), c.isAtiva());
        }
    }

    // ---- produtos ----

    /** grupoIds: na ordem em que os grupos aparecem no produto. */
    public record ProdutoRequest(
            @NotNull UUID categoriaId,
            @NotBlank @Size(max = 120) String nome,
            String descricao,
            String fotoUrl,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal preco,
            @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal precoPromocional,
            int ordem,
            Boolean disponivel,
            List<UUID> grupoIds) {}

    public record ProdutoDto(UUID id, UUID categoriaId, String nome, String descricao, String fotoUrl,
                             BigDecimal preco, BigDecimal precoPromocional, int ordem, boolean disponivel,
                             List<UUID> grupoIds) {
        static ProdutoDto de(Produto p) {
            return new ProdutoDto(p.getId(), p.getCategoria().getId(), p.getNome(), p.getDescricao(), p.getFotoUrl(),
                    p.getPreco(), p.getPrecoPromocional(), p.getOrdem(), p.isDisponivel(),
                    p.getGrupos().stream().map(GrupoAdicional::getId).toList());
        }
    }

    public record DisponibilidadeRequest(@NotNull Boolean disponivel) {}

    // ---- grupos de adicionais ----

    /**
     * opcoes: lista completa e na ordem de exibição. Opção com id é atualizada,
     * sem id é criada, e a que ficar de fora é removida (ids preservados para os carrinhos).
     */
    public record GrupoRequest(
            @NotBlank @Size(max = 80) String nome,
            @Min(0) int minimo,
            @Min(1) int maximo,
            @NotEmpty List<@Valid OpcaoRequest> opcoes) {}

    public record OpcaoRequest(
            UUID id,
            @NotBlank @Size(max = 80) String nome,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal preco,
            Boolean disponivel) {}

    public record GrupoDto(UUID id, String nome, int minimo, int maximo, List<OpcaoDto> opcoes) {
        static GrupoDto de(GrupoAdicional g) {
            // @OrderBy só vale ao carregar do banco; logo após uma edição a lista está na ordem de inserção.
            return new GrupoDto(g.getId(), g.getNome(), g.getMinimo(), g.getMaximo(),
                    g.getOpcoes().stream()
                            .sorted(Comparator.comparingInt(OpcaoAdicional::getOrdem))
                            .map(OpcaoDto::de).toList());
        }
    }

    public record OpcaoDto(UUID id, String nome, BigDecimal preco, boolean disponivel, int ordem) {
        static OpcaoDto de(OpcaoAdicional o) {
            return new OpcaoDto(o.getId(), o.getNome(), o.getPreco(), o.isDisponivel(), o.getOrdem());
        }
    }
}
