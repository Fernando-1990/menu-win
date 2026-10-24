package br.com.menuwin.pagamento;

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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Homologação sem conta no Mercado Pago: o simulador aprova/expira pelo mesmo caminho do webhook. */
@SpringBootTest(properties = "menuwin.pix.simulado=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class PixSimuladoTest {

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

    Loja loja;
    UUID produto;

    @BeforeEach
    void montar() throws Exception {
        loja = novaLoja();
        produto = novoProduto(loja);
        // no modo simulado, qualquer texto serve como token
        mvc.perform(put("/api/painel/pagamentos/mercadopago").header("Authorization", "Bearer " + token(loja))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"qualquer-coisa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulado").value(true));
    }

    @Test
    void aprovarLevaParaACozinhaUmaVezSo() throws Exception {
        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.loja.aceitaPix").value(true))
                .andExpect(jsonPath("$.loja.pixSimulado").value(true));

        String body = pedirPix(loja).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"))
                .andReturn().getResponse().getContentAsString();
        String pedidoId = JsonPath.read(body, "$.id");
        assertThat((String) JsonPath.read(body, "$.pix.copiaECola")).contains("SIMULADO");

        simular(loja, pedidoId, "APROVADO").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.statusPagamento").value("APROVADO"));
        simular(loja, pedidoId, "APROVADO").andExpect(status().isOk());   // repetido: idempotente

        assertThat(jdbc.queryForObject("select count(*) from historico_status_pedido where pedido_id = ?",
                Integer.class, UUID.fromString(pedidoId))).isEqualTo(2);   // AGUARDANDO + RECEBIDO

        // a cozinha recebe o pedido normalmente
        mvc.perform(get("/api/painel/pedidos/abertos").header("Authorization", "Bearer " + token(loja)))
                .andExpect(jsonPath("$[0].id").value(pedidoId));
    }

    @Test
    void expirarCancelaEOutraLojaNaoMexe() throws Exception {
        String pedidoId = JsonPath.read(pedirPix(loja).andReturn().getResponse().getContentAsString(), "$.id");

        Loja outra = novaLoja();
        simular(outra, pedidoId, "APROVADO").andExpect(status().isNotFound());   // pedido de outra loja

        simular(loja, pedidoId, "PENDENTE").andExpect(status().isBadRequest());
        simular(loja, pedidoId, "EXPIRADO")
                .andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.motivoCancelamento").value("Pix não pago no prazo"));
        simular(loja, pedidoId, "APROVADO")   // pago depois de expirado: não volta para a cozinha
                .andExpect(jsonPath("$.status").value("CANCELADO"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions pedirPix(Loja l) throws Exception {
        return mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipoEntrega\":\"RETIRADA\",\"metodoPagamento\":\"PIX\","
                        + "\"cliente\":{\"nome\":\"Ana\",\"telefone\":\"11955554444\",\"email\":\"ana@exemplo.com\"},"
                        + "\"itens\":[{\"produtoId\":\"" + produto + "\"}]}"));
    }

    private ResultActions simular(Loja l, String pedidoId, String status) throws Exception {
        return mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/simulador-pix/" + pedidoId + "?status=" + status));
    }

    private Loja novaLoja() {
        Loja l = new Loja();
        l.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        l.setNome("Padaria");
        return lojaRepository.save(l);
    }

    private UUID novoProduto(Loja l) {
        TenantContext.set(l.getId());
        try {
            Categoria c = new Categoria();
            c.setNome("Bolos");
            categoriaRepository.save(c);
            Produto p = new Produto();
            p.setCategoria(c);
            p.setNome("Bolo de fubá");
            p.setPreco(new BigDecimal("15.00"));
            return produtoRepository.save(p).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private String token(Loja l) throws Exception {
        Usuario u = new Usuario();
        u.setLojaId(l.getId());
        u.setNome("Dono");
        u.setEmail("dono-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com");
        u.setSenhaHash(passwordEncoder.encode("senha"));
        u.setPapel(Papel.DONO);
        usuarioRepository.save(u);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"senha\":\"senha\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
