package br.com.menuwin.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService service;

    @PostMapping("/api/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return service.login(req);
    }

    @GetMapping("/api/painel/me")
    public SessaoResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.sessaoAtual(UUID.fromString(jwt.getSubject()));
    }
}
