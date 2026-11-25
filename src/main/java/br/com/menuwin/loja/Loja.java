package br.com.menuwin.loja;

import br.com.menuwin.shared.Endereco;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A loja É o tenant, por isso não tem @TenantId.
 *
 * A coluna proximo_numero_pedido NÃO é mapeada de propósito: se fosse, salvar a loja
 * pelo painel poderia sobrescrever o contador e repetir números de pedido.
 * Ela será usada só via UPDATE ... RETURNING no passo de pedidos.
 */
@Entity
@Table(name = "lojas")
@Getter
@Setter
public class Loja {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false, length = 120)
    private String nome;

    private String documento;
    private String telefone;

    @JdbcTypeCode(SqlTypes.JSON)
    private Endereco endereco;

    // identidade visual
    private String logoUrl;
    private String capaUrl;

    @Column(nullable = false, length = 7)
    private String corPrimaria = "#C0392B";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Layout layout = Layout.LISTA;

    private boolean mostrarSoComFoto;

    // operação
    private boolean aceitaRetirada = true;
    private boolean aceitaDelivery;
    private boolean aceitaConsumoLocal;
    private boolean aceitaPagarNoCaixa;

    @Column(nullable = false)
    private BigDecimal taxaEntregaPadrao = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal pedidoMinimo = BigDecimal.ZERO;

    private int tempoPreparoMin = 30;
    private boolean ativa = true;

    /** Pausa manual do painel. Vale até alguém despausar: não volta sozinha. */
    private boolean pedidosPausados;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant criadoEm;

    @UpdateTimestamp
    private Instant atualizadoEm;

    @OneToMany(mappedBy = "loja", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("diaSemana, abre")
    private List<HorarioFuncionamento> horarios = new ArrayList<>();

    /** Ainda não há fuso por loja: todas no horário de Brasília. */
    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    /** Aceita pedido agora: não está pausada e está no horário. */
    public boolean estaAberta(LocalDateTime agora) {
        return !pedidosPausados && estaNoHorario(agora);
    }

    /** Sem horário cadastrado = sempre no horário (MVP: a padaria pode começar sem configurar). */
    public boolean estaNoHorario(LocalDateTime agora) {
        return horarios.isEmpty() || horarios.stream().anyMatch(h -> h.cobre(agora));
    }

    /**
     * Quando o próximo horário começa, para o cardápio dizer "abre amanhã às 06:00".
     * Vazio se já está no horário ou se não há horário cadastrado. A pausa não entra: não tem hora para acabar.
     */
    public Optional<LocalDateTime> proximaAbertura(LocalDateTime agora) {
        if (estaNoHorario(agora)) {
            return Optional.empty();
        }
        return horarios.stream()
                .map(h -> {
                    int dias = Math.floorMod(h.getDiaSemana() - agora.getDayOfWeek().getValue(), 7);
                    LocalDateTime inicio = agora.toLocalDate().plusDays(dias).atTime(h.getAbre());
                    return inicio.isAfter(agora) ? inicio : inicio.plusDays(7);
                })
                .min(Comparator.naturalOrder());
    }

    public boolean estaAbertaAgora() {
        return estaAberta(LocalDateTime.now(FUSO));
    }
}
