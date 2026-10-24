package br.com.menuwin.loja;

import br.com.menuwin.loja.OperacaoLojaService.Operacao;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Só DONO (SecurityConfig). PUT recebe o bloco inteiro: o painel manda todos os campos de uma vez. */
@RestController
@RequestMapping("/api/painel/loja/operacao")
@RequiredArgsConstructor
public class OperacaoLojaController {

    private final OperacaoLojaService service;

    @GetMapping
    public Operacao consultar() {
        return service.consultar();
    }

    @PutMapping
    public Operacao atualizar(@Valid @RequestBody Operacao operacao) {
        return service.atualizar(operacao);
    }
}
