package br.com.menuwin.pedido;

import br.com.menuwin.cardapio.Categoria;
import br.com.menuwin.cardapio.CategoriaRepository;
import br.com.menuwin.cardapio.Produto;
import br.com.menuwin.cardapio.ProdutoRepository;
import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tela da cozinha/balcão: SSE por loja, andamento do pedido e pagamento recebido no caixa. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class CozinhaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired PasswordEncoder passwordEncoder;

    record Login(String token, UUID usuarioId) {}

    Loja loja;
    UUID produto;
    Login dono, gerente, cozinha;

    @BeforeEach
    void montar() throws Exception {
        loja = novaLoja();
        produto = novoProduto(loja);
        dono = login(loja, Papel.DONO);
        gerente = login(loja, Papel.GERENTE);
        cozinha = login(loja, Papel.COZINHA);
    }

    @Test
    void telaRecebeEventosSoDaPropriaLoja() throws Exception {
        MvcResult telaA = abrirSse(cozinha);
        Loja lojaB = novaLoja();
        MvcResult telaB = abrirSse(login(lojaB, Papel.COZINHA));
        assertThat(telaA.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(conteudo(telaA)).contains("event:conectado");

        String pedidoId = pedir(loja, "CONSUMO_LOCAL", "NO_CAIXA");
        assertThat(conteudo(telaA)).contains("event:pedido").contains(pedidoId).contains("\"status\":\"RECEBIDO\"");

        mudar(pedidoId, "EM_PREPARO", null, cozinha).andExpect(status().isOk());
        assertThat(conteudo(telaA)).contains("\"status\":\"EM_PREPARO\"");

        assertThat(conteudo(telaB)).doesNotContain(pedidoId);   // loja B não vê nada da A
    }

    @Test
    void comeAquiPagaNoCaixaAoSair() throws Exception {
        String pedidoId = pedir(loja, "CONSUMO_LOCAL", "NO_CAIXA");

        abertos(cozinha).andExpect(jsonPath("$[?(@.id=='" + pedidoId + "')].pagamentoPendenteNoBalcao").value(true));

        mudar(pedidoId, "PRONTO", null, cozinha).andExpect(status().isOk());   // pulou EM_PREPARO: permitido
        mudar(pedidoId, "ENTREGUE", null, cozinha).andExpect(status().isOk())
                .andExpect(jsonPath("$.pagamentoPendenteNoBalcao").value(true));

        // entregue mas não pago: continua na tela, destacado
        abertos(cozinha).andExpect(jsonPath("$[*].id", hasItem(pedidoId)));

        mvc.perform(post("/api/painel/pedidos/" + pedidoId + "/pagamento/recebido").header("Authorization", "Bearer " + cozinha.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusPagamento").value("APROVADO"))
                .andExpect(jsonPath("$.pagamentoPendenteNoBalcao").value(false));
        mvc.perform(post("/api/painel/pedidos/" + pedidoId + "/pagamento/recebido").header("Authorization", "Bearer " + cozinha.token()))
                .andExpect(status().isOk());   // clique duplo não quebra

        abertos(cozinha).andExpect(jsonPath("$[*].id", not(hasItem(pedidoId))));   // pago e entregue: sai da tela

        // histórico com quem mexeu
        List<String> historico = jdbc.queryForList("""
                select status || ':' || coalesce(usuario_id::text, 'sistema') from historico_status_pedido
                where pedido_id = ? order by criado_em""", String.class, UUID.fromString(pedidoId));
        assertThat(historico).containsExactly("RECEBIDO:sistema", "PRONTO:" + cozinha.usuarioId(), "ENTREGUE:" + cozinha.usuarioId());
    }

    @Test
    void regrasDeAndamento() throws Exception {
        String balcao = pedir(loja, "RETIRADA", "NO_CAIXA");
        mudar(balcao, "SAIU_PARA_ENTREGA", null, cozinha).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Só pedido de delivery sai para entrega"));
        mudar(balcao, "PRONTO", null, cozinha).andExpect(status().isOk());
        mudar(balcao, "EM_PREPARO", null, cozinha).andExpect(status().isOk());   // voltar: clique errado
        mudar(balcao, "AGUARDANDO_PAGAMENTO", null, cozinha).andExpect(status().isBadRequest());

        String entrega = pedir(loja, "DELIVERY", "DINHEIRO_NA_ENTREGA");
        mudar(entrega, "SAIU_PARA_ENTREGA", null, cozinha).andExpect(status().isOk());
        mudar(entrega, "ENTREGUE", null, cozinha).andExpect(status().isOk());
        mudar(entrega, "ENTREGUE", null, cozinha).andExpect(status().isOk());    // repetido: ok
        mudar(entrega, "EM_PREPARO", null, cozinha).andExpect(status().isConflict());

        // cancelar: só dono/gerente, com motivo
        mudar(balcao, "CANCELADO", "cliente desistiu", cozinha).andExpect(status().isForbidden());
        mudar(balcao, "CANCELADO", " ", gerente).andExpect(status().isBadRequest());
        mudar(balcao, "CANCELADO", "cliente desistiu", gerente).andExpect(status().isOk())
                .andExpect(jsonPath("$.motivoCancelamento").value("cliente desistiu"));
        mvc.perform(post("/api/painel/pedidos/" + balcao + "/pagamento/recebido").header("Authorization", "Bearer " + dono.token()))
                .andExpect(status().isConflict());

        // aguardando Pix não vai para a cozinha na mão; pagamento online não se marca no caixa
        String pix = pedir(loja, "RETIRADA", "NO_CAIXA");
        jdbc.update("update pedidos set status = 'AGUARDANDO_PAGAMENTO' where id = ?", UUID.fromString(pix));
        jdbc.update("update pagamentos set metodo = 'PIX' where pedido_id = ?", UUID.fromString(pix));
        mudar(pix, "EM_PREPARO", null, cozinha).andExpect(status().isConflict());
        mvc.perform(post("/api/painel/pedidos/" + pix + "/pagamento/recebido").header("Authorization", "Bearer " + dono.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Pagamento online é confirmado automaticamente pelo Mercado Pago"));
        abertos(cozinha).andExpect(jsonPath("$[*].id", not(hasItem(pix))));
    }

    @Test
    void outraLojaNaoMexeNosPedidos() throws Exception {
        String pedidoId = pedir(loja, "CONSUMO_LOCAL", "NO_CAIXA");
        Loja lojaB = novaLoja();
        Login donoB = login(lojaB, Papel.DONO);

        mudar(pedidoId, "PRONTO", null, donoB).andExpect(status().isNotFound());
        mvc.perform(post("/api/painel/pedidos/" + pedidoId + "/pagamento/recebido").header("Authorization", "Bearer " + donoB.token()))
                .andExpect(status().isNotFound());
        abertos(donoB).andExpect(jsonPath("$[*].id", not(hasItem(pedidoId))));
        abertos(cozinha).andExpect(jsonPath("$[?(@.id=='" + pedidoId + "')].status").value("RECEBIDO"));
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult abrirSse(Login login) throws Exception {
        return mvc.perform(get("/api/painel/pedidos/eventos").header("Authorization", "Bearer " + login.token())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String conteudo(MvcResult sse) throws Exception {
        return sse.getResponse().getContentAsString();
    }

    private ResultActions abertos(Login login) throws Exception {
        return mvc.perform(get("/api/painel/pedidos/abertos").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk());
    }

    private ResultActions mudar(String pedidoId, String status, String motivo, Login login) throws Exception {
        String corpo = "{\"status\":\"" + status + "\"" + (motivo == null ? "" : ",\"motivo\":\"" + motivo + "\"") + "}";
        return mvc.perform(patch("/api/painel/pedidos/" + pedidoId + "/status").header("Authorization", "Bearer " + login.token())
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private String pedir(Loja l, String tipo, String metodo) throws Exception {
        String endereco = tipo.equals("DELIVERY")
                ? "\"endereco\":{\"logradouro\":\"Rua A\",\"numero\":\"1\",\"bairro\":\"Centro\",\"cidade\":\"SP\",\"uf\":\"SP\"},"
                : "";
        String body = mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoEntrega\":\"" + tipo + "\",\"metodoPagamento\":\"" + metodo + "\"," + endereco
                                + "\"cliente\":{\"nome\":\"Ana\",\"telefone\":\"11966665555\"},"
                                + "\"itens\":[{\"produtoId\":\"" + produto + "\",\"quantidade\":2,\"observacao\":\"bem passado\"}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private Loja novaLoja() {
        Loja l = new Loja();
        l.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        l.setNome("Padaria");
        l.setAceitaDelivery(true);
        l.setAceitaConsumoLocal(true);
        l.setAceitaPagarNoCaixa(true);
        return lojaRepository.save(l);
    }

    private UUID novoProduto(Loja l) {
        TenantContext.set(l.getId());
        try {
            Categoria c = new Categoria();
            c.setNome("Lanches");
            categoriaRepository.save(c);
            Produto p = new Produto();
            p.setCategoria(c);
            p.setNome("Misto quente");
            p.setPreco(new BigDecimal("12.00"));
            return produtoRepository.save(p).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private Login login(Loja l, Papel papel) throws Exception {
        Usuario u = new Usuario();
        u.setLojaId(l.getId());
        u.setNome(papel.name());
        u.setEmail(papel.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com");
        u.setSenhaHash(passwordEncoder.encode("senha"));
        u.setPapel(papel);
        u = usuarioRepository.save(u);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"senha\":\"senha\"}"))
                .andReturn().getResponse().getContentAsString();
        return new Login(JsonPath.read(body, "$.token"), u.getId());
    }
}
