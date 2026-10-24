package br.com.menuwin.loja;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Sem banco: só a regra de horário. 2026-10-26 é uma segunda-feira (dia 1). */
class HorarioFuncionamentoTest {

    @Test
    void horarioNormalNoMesmoDia() {
        HorarioFuncionamento segunda = horario(1, "06:00", "12:00");
        assertThat(segunda.cobre(em("2026-10-26T06:00"))).isTrue();
        assertThat(segunda.cobre(em("2026-10-26T11:59"))).isTrue();
        assertThat(segunda.cobre(em("2026-10-26T12:00"))).isFalse();   // fecha é exclusivo
        assertThat(segunda.cobre(em("2026-10-26T05:59"))).isFalse();
        assertThat(segunda.cobre(em("2026-10-27T08:00"))).isFalse();   // terça
    }

    @Test
    void horarioQueViraANoitePertenceAoDiaQueAbriu() {
        HorarioFuncionamento domingo = horario(7, "18:00", "02:00");
        assertThat(domingo.cobre(em("2026-10-25T18:00"))).isTrue();   // domingo à noite
        assertThat(domingo.cobre(em("2026-10-26T01:30"))).isTrue();   // madrugada de segunda
        assertThat(domingo.cobre(em("2026-10-26T02:00"))).isFalse();
        assertThat(domingo.cobre(em("2026-10-26T18:30"))).isFalse();  // segunda à noite: outro dia
        assertThat(domingo.cobre(em("2026-10-25T01:00"))).isFalse();  // madrugada de domingo: vem do sábado
    }

    @Test
    void lojaSemHorarioEstaSempreAberta() {
        Loja loja = new Loja();
        assertThat(loja.estaAberta(em("2026-10-26T03:00"))).isTrue();
        loja.getHorarios().add(horario(1, "06:00", "12:00"));
        assertThat(loja.estaAberta(em("2026-10-26T03:00"))).isFalse();
        assertThat(loja.estaAberta(em("2026-10-26T07:00"))).isTrue();
    }

    private static HorarioFuncionamento horario(int dia, String abre, String fecha) {
        HorarioFuncionamento h = new HorarioFuncionamento();
        h.setDiaSemana((short) dia);
        h.setAbre(LocalTime.parse(abre));
        h.setFecha(LocalTime.parse(fecha));
        return h;
    }

    private static LocalDateTime em(String iso) {
        return LocalDateTime.parse(iso);
    }
}
