package br.com.menuwin.loja;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "horarios_funcionamento")
@Getter
@Setter
public class HorarioFuncionamento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loja_id")
    private Loja loja;

    /** 1 = segunda ... 7 = domingo (igual a java.time.DayOfWeek#getValue). */
    @Column(nullable = false)
    private Short diaSemana;

    @Column(nullable = false)
    private LocalTime abre;

    @Column(nullable = false)
    private LocalTime fecha;

    /**
     * Se o horário cobre o instante (no fuso da loja). Quando fecha antes de abrir (18:00–02:00),
     * o período vira a noite e pertence ao dia em que abriu.
     */
    public boolean cobre(LocalDateTime agora) {
        int dia = agora.getDayOfWeek().getValue();
        LocalTime hora = agora.toLocalTime();
        if (abre.isBefore(fecha)) {
            return dia == diaSemana && !hora.isBefore(abre) && hora.isBefore(fecha);
        }
        int diaAnterior = dia == 1 ? 7 : dia - 1;
        return (dia == diaSemana && !hora.isBefore(abre))
                || (diaAnterior == diaSemana && hora.isBefore(fecha));
    }
}
