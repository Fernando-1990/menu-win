package br.com.menuwin.tenant;

import br.com.menuwin.loja.LojaRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rotas públicas do cardápio: /api/public/lojas/{slug}/...
 * Descobre a loja pelo slug e coloca no TenantContext.
 *
 * Rotas do painel (/api/painel/**) recebem a loja do token JWT (auth.PainelTenantFilter).
 */
@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private static final Pattern ROTA_PUBLICA = Pattern.compile("^/api/public/lojas/([a-z0-9-]+)(/.*)?$");

    private final LojaRepository lojaRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Matcher m = ROTA_PUBLICA.matcher(request.getRequestURI());
            if (m.matches()) {
                Optional<UUID> lojaId = lojaRepository.findIdAtivaBySlug(m.group(1));
                if (lojaId.isEmpty()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Loja não encontrada");
                    return;
                }
                TenantContext.set(lojaId.get());
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
