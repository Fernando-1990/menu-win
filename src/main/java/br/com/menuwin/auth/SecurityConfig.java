package br.com.menuwin.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * API stateless:
 *  - /api/public/**      livre (loja resolvida pelo slug no TenantFilter)
 *  - /api/auth/login     livre
 *  - /api/painel/**      exige JWT; a loja vem do claim lojaId (PainelTenantFilter)
 *  - resto               negado
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // sem cookie de sessão: token vai no header
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .authorizeHttpRequests(auth -> auth
                        // o despacho ASYNC do SSE é continuação de uma requisição já autorizada
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/public/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // "acabou hoje" é coisa da cozinha; o resto do cardápio, só DONO e GERENTE
                        .requestMatchers(HttpMethod.PATCH, "/api/painel/cardapio/produtos/*/disponibilidade")
                                .hasAnyRole("DONO", "GERENTE", "COZINHA")
                        .requestMatchers("/api/painel/cardapio/**").hasAnyRole("DONO", "GERENTE")
                        .requestMatchers("/api/painel/pagamentos/**").hasRole("DONO")   // conta onde o dinheiro cai
                        .requestMatchers("/api/painel/loja/**").hasRole("DONO")
                        .requestMatchers("/api/painel/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(papelComoRole())))
                .addFilterAfter(new PainelTenantFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chave(props)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.EMISSOR));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave(props)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Claim "papel" (DONO, GERENTE, COZINHA) vira ROLE_DONO etc. */
    private static JwtAuthenticationConverter papelComoRole() {
        JwtGrantedAuthoritiesConverter papeis = new JwtGrantedAuthoritiesConverter();
        papeis.setAuthoritiesClaimName(TokenService.CLAIM_PAPEL);
        papeis.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(papeis);
        return converter;
    }

    private static SecretKey chave(JwtProperties props) {
        return new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
