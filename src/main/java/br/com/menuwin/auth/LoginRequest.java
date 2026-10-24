package br.com.menuwin.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

public record LoginRequest(@NotBlank @Email String email, @NotBlank String senha) {

    /** Normaliza antes da validação: teclado de celular costuma deixar espaço no fim. */
    public LoginRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }
}
