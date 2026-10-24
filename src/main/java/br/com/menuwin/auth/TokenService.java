package br.com.menuwin.auth;

import br.com.menuwin.usuario.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Emite o JWT do painel: sub = id do usuário, lojaId e papel como claims. */
@Service
@RequiredArgsConstructor
public class TokenService {

    static final String EMISSOR = "menu-win";
    static final String CLAIM_LOJA = "lojaId";
    static final String CLAIM_PAPEL = "papel";

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public LoginResponse emitir(Usuario usuario) {
        Instant agora = Instant.now();
        Instant expiraEm = agora.plus(props.expiracao());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(EMISSOR)
                .subject(usuario.getId().toString())
                .issuedAt(agora)
                .expiresAt(expiraEm)
                .claim(CLAIM_LOJA, usuario.getLojaId().toString())
                .claim(CLAIM_PAPEL, usuario.getPapel().name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new LoginResponse(token, "Bearer", expiraEm);
    }
}
