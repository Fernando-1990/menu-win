package br.com.menuwin.auth;

import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.tenant.TenantContext;
import br.com.menuwin.usuario.Papel;
import br.com.menuwin.usuario.Usuario;
import br.com.menuwin.usuario.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login do painel e loja vinda do token: cada usuário só entra na própria loja,
 * e o cardápio público continua aberto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AutenticacaoPainelTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SENHA = "senha-certa";

    @Autowired MockMvc mvc;
    @Autowired LojaRepository lojaRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void cadaUsuarioEntraNaPropriaLoja() throws Exception {
        Loja lojaA = criarLoja(true);
        Loja lojaB = criarLoja(true);
        String emailA = criarUsuario(lojaA, true);
        String emailB = criarUsuario(lojaB, true);

        mvc.perform(get("/api/painel/me").header("Authorization", "Bearer " + login(emailA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lojaId").value(lojaA.getId().toString()))
                .andExpect(jsonPath("$.lojaSlug").value(lojaA.getSlug()))
                .andExpect(jsonPath("$.papel").value("DONO"));

        mvc.perform(get("/api/painel/me").header("Authorization", "Bearer " + login(emailB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lojaId").value(lojaB.getId().toString()));

        assertThat(TenantContext.get()).isEmpty();   // filtro limpou no finally
    }

    @Test
    void emailIgnoraMaiusculasEEspacos() throws Exception {
        String email = criarUsuario(criarLoja(true), true);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("  " + email.toUpperCase() + " ", SENHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("Bearer"));
    }

    @Test
    void credenciaisInvalidasDevolvem401() throws Exception {
        String email = criarUsuario(criarLoja(true), true);
        tentarLogin(email, "senha-errada", 401);
        tentarLogin("ninguem@exemplo.com", SENHA, 401);
        tentarLogin("nao-e-email", SENHA, 400);
    }

    @Test
    void usuarioOuLojaInativosNaoEntram() throws Exception {
        tentarLogin(criarUsuario(criarLoja(true), false), SENHA, 401);
        tentarLogin(criarUsuario(criarLoja(false), true), SENHA, 401);
    }

    @Test
    void painelExigeTokenValido() throws Exception {
        mvc.perform(get("/api/painel/me")).andExpect(status().isUnauthorized());

        String token = login(criarUsuario(criarLoja(true), true));
        String adulterado = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        mvc.perform(get("/api/painel/me").header("Authorization", "Bearer " + adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cardapioPublicoContinuaAbertoEOResto() throws Exception {
        Loja loja = criarLoja(true);
        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio")).andExpect(status().isOk());
        mvc.perform(get("/api/public/lojas/nao-existe/cardapio")).andExpect(status().isNotFound());
        mvc.perform(get("/api/qualquer-outra-coisa")).andExpect(status().isUnauthorized());
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private void tentarLogin(String email, String senha, int statusEsperado) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json(email, senha)))
                .andExpect(status().is(statusEsperado));
    }

    private static String json(String email, String senha) {
        return "{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}";
    }

    private Loja criarLoja(boolean ativa) {
        Loja loja = new Loja();
        loja.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        loja.setNome("Loja de teste");
        loja.setAtiva(ativa);
        return lojaRepository.save(loja);
    }

    private String criarUsuario(Loja loja, boolean ativo) {
        Usuario u = new Usuario();
        u.setLojaId(loja.getId());   // Usuario não tem @TenantId: a loja é preenchida na mão
        u.setNome("Dono");
        u.setEmail("dono-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com");
        u.setSenhaHash(passwordEncoder.encode(SENHA));
        u.setPapel(Papel.DONO);
        u.setAtivo(ativo);
        return usuarioRepository.save(u).getEmail();
    }
}
