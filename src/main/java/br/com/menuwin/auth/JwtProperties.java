package br.com.menuwin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * menuwin.jwt.secret: chave HS256 (mínimo 32 bytes). Em produção vem de JWT_SECRET;
 * só o profile dev tem valor padrão.
 */
@ConfigurationProperties("menuwin.jwt")
public record JwtProperties(String secret, Duration expiracao) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("menuwin.jwt.secret precisa ter pelo menos 32 bytes (HS256)");
        }
        if (expiracao == null) {
            expiracao = Duration.ofHours(8);
        }
    }
}
