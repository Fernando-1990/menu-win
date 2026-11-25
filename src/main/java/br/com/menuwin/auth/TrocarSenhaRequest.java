package br.com.menuwin.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** novaSenha: 8 a 72 caracteres (72 bytes é o limite do BCrypt; o serviço confere os bytes). */
public record TrocarSenhaRequest(
        @NotBlank String senhaAtual,
        @NotBlank @Size(min = 8, max = 72, message = "A nova senha precisa ter de 8 a 72 caracteres") String novaSenha) {}
