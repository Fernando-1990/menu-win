package br.com.menuwin.pagamento;

import br.com.menuwin.pagamento.CredenciaisService.StatusCredencial;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Só DONO (SecurityConfig). O token nunca é devolvido: só se está configurado e se é de teste. */
@RestController
@RequestMapping("/api/painel/pagamentos/mercadopago")
@RequiredArgsConstructor
public class CredenciaisPainelController {

    private final CredenciaisService service;

    public record CredencialRequest(@NotBlank @Size(max = 300) String accessToken) {}

    @GetMapping
    public StatusCredencial status() {
        return service.statusMercadoPago();
    }

    @PutMapping
    public StatusCredencial salvar(@Valid @RequestBody CredencialRequest req) {
        return service.salvarMercadoPago(req.accessToken());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover() {
        service.removerMercadoPago();
    }
}
