package br.com.menuwin.cardapio;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/lojas/{slug}")
@RequiredArgsConstructor
public class CardapioPublicoController {

    private final CardapioPublicoService service;

    /** O slug já foi resolvido para a loja pelo TenantFilter. */
    @GetMapping("/cardapio")
    public CardapioResponse cardapio(@PathVariable String slug) {
        return service.carregar();
    }
}
