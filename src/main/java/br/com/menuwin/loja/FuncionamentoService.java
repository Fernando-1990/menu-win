package br.com.menuwin.loja;

import br.com.menuwin.tenant.TenantContext;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Quando a loja aceita pedidos: horários da semana (só o DONO muda) e pausa manual (qualquer papel:
 * quem está na cozinha é quem vê que lotou). A loja é sempre a do token.
 */
@Service
@RequiredArgsConstructor
public class FuncionamentoService {

    private final LojaRepository lojaRepository;

    /** 1 = segunda ... 7 = domingo. Fecha antes de abrir = vira a noite (18:00–02:00). */
    public record Horario(
            @NotNull @Min(1) @Max(7) Short diaSemana,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime abre,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime fecha) {}

    /** aberta = aceita pedido agora (fora da pausa e dentro do horário). */
    public record Funcionamento(boolean pedidosPausados, boolean aberta, LocalDateTime abreEm, List<Horario> horarios) {

        static Funcionamento de(Loja l) {
            LocalDateTime agora = LocalDateTime.now(Loja.FUSO);
            List<Horario> horarios = l.getHorarios().stream()
                    .map(h -> new Horario(h.getDiaSemana(), h.getAbre(), h.getFecha()))
                    .toList();
            return new Funcionamento(l.isPedidosPausados(), l.estaAberta(agora),
                    l.proximaAbertura(agora).orElse(null), horarios);
        }
    }

    public record NovosHorarios(@NotNull @Size(max = 28) List<@Valid @NotNull Horario> horarios) {}

    public record Pausa(boolean pausado) {}

    @Transactional(readOnly = true)
    public Funcionamento consultar() {
        return Funcionamento.de(lojaAtual());
    }

    /** Substitui a semana inteira. Lista vazia = sem horário = aceita pedido a qualquer hora. */
    @Transactional
    public Funcionamento trocarHorarios(NovosHorarios novos) {
        for (Horario h : novos.horarios()) {
            if (h.abre().equals(h.fecha())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hora de abrir e a de fechar não podem ser iguais");
            }
        }
        Loja loja = lojaAtual();
        loja.getHorarios().clear();
        for (Horario h : novos.horarios()) {
            HorarioFuncionamento novo = new HorarioFuncionamento();
            novo.setLoja(loja);
            novo.setDiaSemana(h.diaSemana());
            novo.setAbre(h.abre());
            novo.setFecha(h.fecha());
            loja.getHorarios().add(novo);
        }
        lojaRepository.flush();
        return Funcionamento.de(loja);
    }

    @Transactional
    public Funcionamento pausar(Pausa pausa) {
        Loja loja = lojaAtual();
        loja.setPedidosPausados(pausa.pausado());
        return Funcionamento.de(loja);
    }

    private Loja lojaAtual() {
        return lojaRepository.findById(TenantContext.getObrigatorio()).orElseThrow();
    }
}
