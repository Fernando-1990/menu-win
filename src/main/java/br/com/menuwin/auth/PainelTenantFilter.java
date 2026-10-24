package br.com.menuwin.auth;

import br.com.menuwin.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Rotas do painel: /api/painel/**
 * Roda dentro da cadeia do Spring Security, logo depois da validação do JWT,
 * e coloca no TenantContext a loja do claim lojaId.
 *
 * Não é @Component de propósito: senão o Spring Boot também o registraria como filtro do servlet.
 */
class PainelTenantFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/painel/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                TenantContext.set(UUID.fromString(jwt.getToken().getClaimAsString(TokenService.CLAIM_LOJA)));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
