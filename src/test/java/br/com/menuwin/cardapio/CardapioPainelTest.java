package br.com.menuwin.cardapio;

import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD do cardápio no painel: fluxo, isolamento entre lojas, regras e permissões. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class CardapioPainelTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String BASE = "/api/painel/cardapio";
    private static final String SENHA = "senha-certa";

    @Autowired MockMvc mvc;
    @Autowired LojaRepository lojaRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    Loja loja;
    String dono;

    @BeforeEach
    void novaLoja() throws Exception {
        loja = criarLoja();
        dono = token(loja, Papel.DONO);
    }

    @Test
    void fluxoCompletoAparecePublicoESomeAoRemover() throws Exception {
        String categoriaId = criarCategoria(dono, "Lanches");
        String grupoId = criarGrupo(dono, "Escolha o pão", 1, 1, "Francês", "Australiano");

        String produtoId = id(chamar(post(BASE + "/produtos"), dono, produto(categoriaId, "X-Burguer", "25.00", null, grupoId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grupoIds[0]").value(grupoId)));

        chamar(get(BASE + "/grupos/" + grupoId), dono, null)
                .andExpect(jsonPath("$.opcoes", hasSize(2)))
                .andExpect(jsonPath("$.opcoes[1].nome").value("Australiano"))
                .andExpect(jsonPath("$.opcoes[1].ordem").value(1));

        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.categorias[0].produtos[0].nome").value("X-Burguer"));

        chamar(put(BASE + "/produtos/" + produtoId), dono, produto(categoriaId, "X-Burguer duplo", "30.00", "27.50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precoPromocional").value(27.50))
                .andExpect(jsonPath("$.grupoIds", hasSize(0)));

        chamar(delete(BASE + "/produtos/" + produtoId), dono, null).andExpect(status().isNoContent());
        chamar(get(BASE + "/produtos/" + produtoId), dono, null).andExpect(status().isNotFound());
        chamar(get(BASE + "/produtos"), dono, null).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.categorias", hasSize(0)));
    }

    @Test
    void outraLojaNaoEnxergaNemAlteraNada() throws Exception {
        String categoriaA = criarCategoria(dono, "Da A");
        String grupoA = criarGrupo(dono, "Do A", 0, 1, "Opção A");
        String produtoA = id(chamar(post(BASE + "/produtos"), dono, produto(categoriaA, "Produto A", "10.00", null))
                .andExpect(status().isCreated()));

        Loja lojaB = criarLoja();
        String donoB = token(lojaB, Papel.DONO);

        // listar
        chamar(get(BASE + "/categorias"), donoB, null).andExpect(jsonPath("$", hasSize(0)));
        chamar(get(BASE + "/produtos"), donoB, null).andExpect(jsonPath("$", hasSize(0)));
        chamar(get(BASE + "/grupos"), donoB, null).andExpect(jsonPath("$", hasSize(0)));

        // buscar, alterar e apagar por id da loja A
        chamar(get(BASE + "/categorias/" + categoriaA), donoB, null).andExpect(status().isNotFound());
        chamar(put(BASE + "/categorias/" + categoriaA), donoB, "{\"nome\":\"Invadida\"}").andExpect(status().isNotFound());
        chamar(delete(BASE + "/categorias/" + categoriaA), donoB, null).andExpect(status().isNotFound());
        chamar(get(BASE + "/produtos/" + produtoA), donoB, null).andExpect(status().isNotFound());
        chamar(patch(BASE + "/produtos/" + produtoA + "/disponibilidade"), donoB, "{\"disponivel\":false}")
                .andExpect(status().isNotFound());
        chamar(delete(BASE + "/produtos/" + produtoA), donoB, null).andExpect(status().isNotFound());
        chamar(get(BASE + "/grupos/" + grupoA), donoB, null).andExpect(status().isNotFound());
        chamar(delete(BASE + "/grupos/" + grupoA), donoB, null).andExpect(status().isNotFound());

        // usar categoria ou grupo da loja A em produto da loja B
        String categoriaB = criarCategoria(donoB, "Da B");
        chamar(post(BASE + "/produtos"), donoB, produto(categoriaA, "Intruso", "1.00", null))
                .andExpect(status().isBadRequest());
        chamar(post(BASE + "/produtos"), donoB, produto(categoriaB, "Intruso", "1.00", null, grupoA))
                .andExpect(status().isBadRequest());

        // nada da loja A mudou
        chamar(get(BASE + "/produtos/" + produtoA), dono, null)
                .andExpect(jsonPath("$.nome").value("Produto A"))
                .andExpect(jsonPath("$.disponivel").value(true));
        chamar(get(BASE + "/categorias/" + categoriaA), dono, null).andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void regrasDeNegocio() throws Exception {
        String categoriaId = criarCategoria(dono, "Bebidas");

        chamar(post(BASE + "/produtos"), dono, produto(categoriaId, "Suco", "8.00", "8.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O preço promocional deve ser menor que o preço"));
        chamar(post(BASE + "/produtos"), dono, produto(categoriaId, "Suco", "-1.00", null))
                .andExpect(status().isBadRequest());
        chamar(post(BASE + "/grupos"), dono, grupo("Inválido", 2, 1, "A", "B")).andExpect(status().isBadRequest());
        chamar(post(BASE + "/grupos"), dono, grupo("Impossível", 2, 3, "Só uma")).andExpect(status().isBadRequest());

        // categoria com produto ativo não sai; sem produto, fica inativa (e some do público)
        String produtoId = id(chamar(post(BASE + "/produtos"), dono, produto(categoriaId, "Suco", "8.00", null))
                .andExpect(status().isCreated()));
        chamar(delete(BASE + "/categorias/" + categoriaId), dono, null).andExpect(status().isConflict());
        chamar(delete(BASE + "/produtos/" + produtoId), dono, null).andExpect(status().isNoContent());
        chamar(delete(BASE + "/categorias/" + categoriaId), dono, null).andExpect(status().isNoContent());
        chamar(get(BASE + "/categorias/" + categoriaId), dono, null).andExpect(jsonPath("$.ativa").value(false));
    }

    @Test
    void editarGrupoPreservaIdsDasOpcoesMantidas() throws Exception {
        String body = chamar(post(BASE + "/grupos"), dono, grupo("Molhos", 0, 3, "Barbecue", "Mostarda"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String grupoId = JsonPath.read(body, "$.id");
        String barbecue = JsonPath.read(body, "$.opcoes[0].id");
        String mostarda = JsonPath.read(body, "$.opcoes[1].id");

        String edicao = """
                {"nome":"Molhos","minimo":0,"maximo":3,"opcoes":[
                  {"nome":"Maionese verde","preco":"2.00"},
                  {"id":"%s","nome":"Barbecue","preco":"1.50","disponivel":false}
                ]}""".formatted(barbecue);
        String depois = chamar(put(BASE + "/grupos/" + grupoId), dono, edicao)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opcoes", hasSize(2)))
                .andExpect(jsonPath("$.opcoes[0].nome").value("Maionese verde"))
                .andExpect(jsonPath("$.opcoes[1].id").value(barbecue))
                .andExpect(jsonPath("$.opcoes[1].disponivel").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(depois, "$.opcoes[*].id");
        assertThat(ids).doesNotContain(mostarda).doesNotContainNull();

        // id de opção de outro grupo não é aceito
        String outroGrupo = criarGrupo(dono, "Outro", 0, 1, "X");
        String opcaoAlheia = JsonPath.read(chamar(get(BASE + "/grupos/" + outroGrupo), dono, null)
                .andReturn().getResponse().getContentAsString(), "$.opcoes[0].id");
        chamar(put(BASE + "/grupos/" + grupoId), dono,
                "{\"nome\":\"Molhos\",\"minimo\":0,\"maximo\":3,\"opcoes\":[{\"id\":\"%s\",\"nome\":\"X\",\"preco\":\"0\"}]}"
                        .formatted(opcaoAlheia))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apagarOpcaoJaPedidaMantemOHistorico() throws Exception {
        String body = chamar(post(BASE + "/grupos"), dono, grupo("Adicionais", 0, 2, "Bacon"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String grupoId = JsonPath.read(body, "$.id");
        UUID bacon = UUID.fromString(JsonPath.read(body, "$.opcoes[0].id"));
        UUID itemAdicional = pedidoComAdicional(bacon);

        chamar(delete(BASE + "/grupos/" + grupoId), dono, null).andExpect(status().isNoContent());

        var linha = jdbc.queryForMap("select opcao_id, nome_opcao from itens_pedido_adicionais where id = ?", itemAdicional);
        assertThat(linha.get("opcao_id")).isNull();
        assertThat(linha.get("nome_opcao")).isEqualTo("Bacon");
    }

    @Test
    void permissoesPorPapel() throws Exception {
        String cozinha = token(loja, Papel.COZINHA);
        String gerente = token(loja, Papel.GERENTE);

        String categoriaId = criarCategoria(gerente, "Do gerente");
        String produtoId = id(chamar(post(BASE + "/produtos"), gerente, produto(categoriaId, "Pão", "1.00", null))
                .andExpect(status().isCreated()));

        chamar(post(BASE + "/categorias"), cozinha, "{\"nome\":\"Da cozinha\"}").andExpect(status().isForbidden());
        chamar(get(BASE + "/produtos"), cozinha, null).andExpect(status().isForbidden());
        chamar(delete(BASE + "/produtos/" + produtoId), cozinha, null).andExpect(status().isForbidden());
        chamar(patch(BASE + "/produtos/" + produtoId + "/disponibilidade"), cozinha, "{\"disponivel\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponivel").value(false));
        chamar(get(BASE + "/produtos"), null, null).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions chamar(MockHttpServletRequestBuilder req, String token, String json) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(req);
    }

    private static String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String criarCategoria(String token, String nome) throws Exception {
        return id(chamar(post(BASE + "/categorias"), token, "{\"nome\":\"" + nome + "\",\"ordem\":1}")
                .andExpect(status().isCreated()));
    }

    private String criarGrupo(String token, String nome, int min, int max, String... opcoes) throws Exception {
        return id(chamar(post(BASE + "/grupos"), token, grupo(nome, min, max, opcoes)).andExpect(status().isCreated()));
    }

    private static String grupo(String nome, int min, int max, String... opcoes) {
        String lista = String.join(",", java.util.Arrays.stream(opcoes)
                .map(o -> "{\"nome\":\"" + o + "\",\"preco\":\"1.00\"}").toList());
        return "{\"nome\":\"" + nome + "\",\"minimo\":" + min + ",\"maximo\":" + max + ",\"opcoes\":[" + lista + "]}";
    }

    private static String produto(String categoriaId, String nome, String preco, String promo, String... grupoIds) {
        String grupos = String.join(",", java.util.Arrays.stream(grupoIds).map(g -> "\"" + g + "\"").toList());
        return "{\"categoriaId\":\"" + categoriaId + "\",\"nome\":\"" + nome + "\",\"preco\":\"" + preco + "\","
                + "\"precoPromocional\":" + (promo == null ? "null" : "\"" + promo + "\"") + ","
                + "\"grupoIds\":[" + grupos + "]}";
    }

    private Loja criarLoja() {
        Loja l = new Loja();
        l.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        l.setNome("Loja de teste");
        return lojaRepository.save(l);
    }

    private String token(Loja l, Papel papel) throws Exception {
        Usuario u = new Usuario();
        u.setLojaId(l.getId());
        u.setNome(papel.name());
        u.setEmail(papel.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com");
        u.setSenhaHash(passwordEncoder.encode(SENHA));
        u.setPapel(papel);
        usuarioRepository.save(u);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"senha\":\"" + SENHA + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    /** Pedido mínimo direto no banco (a criação de pedido é o passo 5). */
    private UUID pedidoComAdicional(UUID opcaoId) {
        UUID pedido = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID adicional = UUID.randomUUID();
        jdbc.update("insert into pedidos (id, loja_id, numero, tipo_entrega, subtotal, total) values (?, ?, 1, 'RETIRADA', 12, 12)",
                pedido, loja.getId());
        jdbc.update("insert into itens_pedido (id, pedido_id, nome_produto, preco_unitario, quantidade, total_item) values (?, ?, 'Lanche', 10, 1, 12)",
                item, pedido);
        jdbc.update("insert into itens_pedido_adicionais (id, item_pedido_id, opcao_id, nome_grupo, nome_opcao, preco) values (?, ?, ?, 'Adicionais', 'Bacon', 2)",
                adicional, item, opcaoId);
        return adicional;
    }
}
