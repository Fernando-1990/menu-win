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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Configuração da operação pelo dono: consumo no local e pagar no caixa. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class OperacaoLojaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String URL = "/api/painel/loja/operacao";

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
    void novasOpcoesComecamDesligadasESoODonoMuda() throws Exception {
        mvc.perform(get(URL).header("Authorization", "Bearer " + dono))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceitaRetirada").value(true))
                .andExpect(jsonPath("$.aceitaConsumoLocal").value(false))
                .andExpect(jsonPath("$.aceitaPagarNoCaixa").value(false));

        mvc.perform(put(URL).header("Authorization", "Bearer " + token(Papel.GERENTE))
                        .contentType(MediaType.APPLICATION_JSON).content(operacao(true, false, true, true)))
                .andExpect(status().isForbidden());

        mvc.perform(put(URL).header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON).content(operacao(false, false, false, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Habilite pelo menos uma forma de atendimento: retirada, delivery ou consumo no local"));

        mvc.perform(put(URL).header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON).content(operacao(true, false, true, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceitaConsumoLocal").value(true));

        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.loja.aceitaConsumoLocal").value(true))
                .andExpect(jsonPath("$.loja.aceitaPagarNoCaixa").value(true));
    }

    @Test
    void comerAquiEPagarAoSairVaiDiretoParaACozinha() throws Exception {
        // desligado: recusa
        pedir("CONSUMO_LOCAL", "NO_CAIXA").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esta loja não atende consumo no local pelo cardápio"));

        configurar(operacao(true, true, true, false));
        pedir("CONSUMO_LOCAL", "NO_CAIXA").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esta loja não aceita pagar no caixa"));

        configurar(operacao(true, true, true, true));
        String body = pedir("CONSUMO_LOCAL", "NO_CAIXA")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.tipoEntrega").value("CONSUMO_LOCAL"))
                .andExpect(jsonPath("$.metodoPagamento").value("NO_CAIXA"))
                .andExpect(jsonPath("$.pix").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID pedidoId = UUID.fromString(JsonPath.read(body, "$.id"));
        var pagamento = jdbc.queryForMap("select metodo, status, valor from pagamentos where pedido_id = ?", pedidoId);
        assertThat(pagamento.get("metodo")).isEqualTo("NO_CAIXA");
        assertThat(pagamento.get("status")).isEqualTo("PENDENTE");   // o caixa marca como pago (passo 7)
        assertThat((BigDecimal) pagamento.get("valor")).isEqualByComparingTo("7.00");

        // retirada também pode pagar no caixa; delivery não
        pedir("RETIRADA", "NO_CAIXA").andExpect(status().isCreated());
        pedir("DELIVERY", "NO_CAIXA").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Pagar no caixa vale só para retirada ou consumo no local"));
    }

    // ---------------------------------------------------------------- helpers

    private static String operacao(boolean retirada, boolean delivery, boolean consumoLocal, boolean noCaixa) {
        return """
                {"aceitaRetirada":%s,"aceitaDelivery":%s,"aceitaConsumoLocal":%s,"aceitaPagarNoCaixa":%s,
                 "taxaEntregaPadrao":"5.00","pedidoMinimo":"0.00","tempoPreparoMin":20}"""
                .formatted(retirada, delivery, consumoLocal, noCaixa);
    }

    private void configurar(String json) throws Exception {
        mvc.perform(put(URL).header("Authorization", "Bearer " + dono).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private ResultActions pedir(String tipo, String metodo) throws Exception {
        String endereco = tipo.equals("DELIVERY")
                ? "\"endereco\":{\"logradouro\":\"Rua A\",\"numero\":\"1\",\"bairro\":\"Centro\",\"cidade\":\"SP\",\"uf\":\"SP\"},"
                : "";
        return mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipoEntrega\":\"" + tipo + "\",\"metodoPagamento\":\"" + metodo + "\"," + endereco
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
