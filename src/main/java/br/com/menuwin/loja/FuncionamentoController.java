package br.com.menuwin.loja;

import br.com.menuwin.loja.FuncionamentoService.Funcionamento;
import br.com.menuwin.loja.FuncionamentoService.NovosHorarios;
import br.com.menuwin.loja.FuncionamentoService.Pausa;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** GET e pausa: todos os papéis. Horários: só DONO (SecurityConfig). */
@RestController
@RequestMapping("/api/painel/funcionamento")
@RequiredArgsConstructor
public class FuncionamentoController {

    private final FuncionamentoService service;

    @GetMapping
    public Funcionamento consultar() {
        return service.consultar();
    }

    @PutMapping("/horarios")
    public Funcionamento trocarHorarios(@Valid @RequestBody NovosHorarios novos) {
        return service.trocarHorarios(novos);
    }

    @PutMapping("/pausa")
    public Funcionamento pausar(@RequestBody Pausa pausa) {
        return service.pausar(pausa);
    }
}
