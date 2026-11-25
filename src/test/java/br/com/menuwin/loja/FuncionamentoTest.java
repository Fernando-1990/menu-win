package br.com.menuwin.loja;

import br.com.menuwin.cardapio.Categoria;
import br.com.menuwin.cardapio.CategoriaRepository;
import br.com.menuwin.cardapio.Produto;
import br.com.menuwin.cardapio.ProdutoRepository;
import br.com.menuwin.tenant.TenantContext;
import br.com.menuwin.usuario.Papel;
import br.com.menuwin.usuario.Usuario;
import br.com.menuwin.usuario.UsuarioRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Horários da semana (só o dono) e pausa de pedidos (qualquer papel). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class FuncionamentoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String URL = "/api/painel/funcionamento";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Loja loja;
    UUID produto;
    String dono;

    @BeforeEach
    void montar() throws Exception {
        loja = new Loja();
        loja.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        loja.setNome("Padaria");
        loja = lojaRepository.save(loja);
        TenantContext.set(loja.getId());
        try {
            Categoria c = new Categoria();
            c.setNome("Pães");
            categoriaRepository.save(c);
            Produto p = new Produto();
            p.setCategoria(c);
            p.setNome("Pão na chapa");
            p.setPreco(new BigDecimal("7.00"));
            produto = produtoRepository.save(p).getId();
        } finally {
            TenantContext.clear();
        }
        dono = token(Papel.DONO);
    }


    @Test
    void pausaQualquerPapelEOCardapioMostra() throws Exception {
        String cozinha = token(Papel.COZINHA);
        pausar(cozinha, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidosPausados").value(true))
                .andExpect(jsonPath("$.aberta").value(false));

        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.loja.aberta").value(false))
                .andExpect(jsonPath("$.loja.pausada").value(true));
        pedir().andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A loja pausou os pedidos por alguns instantes. Tente de novo daqui a pouco"));

        pausar(dono, false).andExpect(jsonPath("$.aberta").value(true));
        pedir().andExpect(status().isCreated());
    }

    @Test
    void soODonoTrocaOsHorariosEOCardapioDizQuandoAbre() throws Exception {
        LocalDate amanha = LocalDate.now(Loja.FUSO).plusDays(1);
        String semana = "{\"horarios\":[{\"diaSemana\":%d,\"abre\":\"10:00\",\"fecha\":\"11:00\"}]}"
                .formatted(amanha.getDayOfWeek().getValue());

        mvc.perform(put(URL + "/horarios").header("Authorization", "Bearer " + token(Papel.GERENTE))
                        .contentType(MediaType.APPLICATION_JSON).content(semana))
                .andExpect(status().isForbidden());

        mvc.perform(put(URL + "/horarios").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horarios\":[{\"diaSemana\":1,\"abre\":\"10:00\",\"fecha\":\"10:00\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A hora de abrir e a de fechar não podem ser iguais"));
        mvc.perform(put(URL + "/horarios").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horarios\":[{\"diaSemana\":8,\"abre\":\"10:00\",\"fecha\":\"11:00\"}]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put(URL + "/horarios").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON).content(semana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horarios[0].abre").value("10:00"))
                .andExpect(jsonPath("$.horarios[0].fecha").value("11:00"))
                .andExpect(jsonPath("$.aberta").value(false))
                .andExpect(jsonPath("$.abreEm").value(amanha + "T10:00:00"));

        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.loja.aberta").value(false))
                .andExpect(jsonPath("$.loja.pausada").value(false))
                .andExpect(jsonPath("$.loja.abreEm").value(amanha + "T10:00:00"));
        pedir().andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("A loja está fechada agora"));

        // trocar de novo substitui (não soma); lista vazia volta a aceitar a qualquer hora
        mvc.perform(put(URL + "/horarios").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"horarios\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horarios", hasSize(0)))
                .andExpect(jsonPath("$.aberta").value(true));
        assertThat(jdbc.queryForObject("select count(*) from horarios_funcionamento where loja_id = ?",
                Integer.class, loja.getId())).isZero();
        mvc.perform(get(URL).header("Authorization", "Bearer " + token(Papel.COZINHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abreEm").doesNotExist());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions pausar(String token, boolean pausado) throws Exception {
        return mvc.perform(put(URL + "/pausa").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pausado\":" + pausado + "}"));
    }

    private ResultActions pedir() throws Exception {
        return mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipoEntrega\":\"RETIRADA\",\"metodoPagamento\":\"CARTAO_NA_ENTREGA\","
                        + "\"cliente\":{\"nome\":\"João\",\"telefone\":\"11977776666\"},"
                        + "\"itens\":[{\"produtoId\":\"" + produto + "\"}]}"));
    }

    private String token(Papel papel) throws Exception {
        Usuario u = new Usuario();
        u.setLojaId(loja.getId());
        u.setNome(papel.name());
        u.setEmail(papel.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com");
        u.setSenhaHash(passwordEncoder.encode("senha"));
        u.setPapel(papel);
        usuarioRepository.save(u);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"senha\":\"senha\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
