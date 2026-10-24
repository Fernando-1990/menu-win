package br.com.menuwin;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Os outros testes não carregam o seed de dev (db/dev). Este aplica o seed como o profile dev faz
 * e confere que o login documentado no comentário dele funciona de ponta a ponta.
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/dev")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class SeedDevTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedCriaTudoEOLoginDoComentarioFunciona() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from lojas", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from usuarios", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from produtos", Integer.class)).isEqualTo(4);

        // o login documentado no comentário do seed é o que vale
        String seed = new ClassPathResource("db/dev/R__seed_dev.sql").getContentAsString(StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("Login do painel \\(só dev\\): (\\S+) / (\\S+)").matcher(seed);
        assertThat(m.find()).isTrue();

        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + m.group(1) + "\",\"senha\":\"" + m.group(2) + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/painel/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lojaSlug").value("padaria-demo"));
    }
}
