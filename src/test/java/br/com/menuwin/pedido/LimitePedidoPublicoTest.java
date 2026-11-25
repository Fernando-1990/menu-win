package br.com.menuwin.pedido;

import br.com.menuwin.cardapio.Categoria;
import br.com.menuwin.cardapio.CategoriaRepository;
import br.com.menuwin.cardapio.Produto;
import br.com.menuwin.cardapio.ProdutoRepository;
import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Limite de pedidos pela API, com tetos baixos só nesta classe. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "menuwin.pedidos.limite.telefone.maximo=2",
        "menuwin.pedidos.limite.ip.maximo=3",
        "menuwin.pedidos.limite.loja.maximo=4"})
class LimitePedidoPublicoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;
    @Autowired ProdutoRepository produtoRepository;

    Loja loja;
    UUID cafe;

    @BeforeEach
    void montarLoja() {
        loja = new Loja();
        loja.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));   // loja nova = contagem zerada
        loja.setNome("Café");
        loja = lojaRepository.save(loja);
        TenantContext.set(loja.getId());
        Categoria bebidas = new Categoria();
        bebidas.setNome("Bebidas");
        categoriaRepository.save(bebidas);
        Produto p = new Produto();
        p.setCategoria(bebidas);
        p.setNome("Café");
        p.setPreco(new BigDecimal("5.00"));
        cafe = produtoRepository.save(p).getId();
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void mesmoTelefoneNoLimiteRecebe429ComMensagemERetryAfter() throws Exception {
        pedir("1.1.1.1", "11988887777", null).andExpect(status().isCreated());
        pedir("1.1.1.2", "11988887777", null).andExpect(status().isCreated());
        pedir("1.1.1.3", "(11) 98888-7777", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(containsString("telefone")));
        pedir("1.1.1.3", "11977776666", null).andExpect(status().isCreated());   // outro cliente segue normal
    }

    @Test
    void reenvioComAMesmaChaveNaoGastaACota() throws Exception {
        String chave = UUID.randomUUID().toString();
        pedir("2.2.2.2", "11955554444", chave).andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            pedir("2.2.2.2", "11955554444", chave).andExpect(status().isOk());
        }
        pedir("2.2.2.2", "11955554444", null).andExpect(status().isCreated());   // 2º pedido de verdade ainda cabe
    }

    @Test
    void pedidoRecusadoNaoGastaACota() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos")
                            .with(r -> { r.setRemoteAddr("3.3.3.3"); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(corpo("11944443333").replace("RETIRADA", "DELIVERY")))   // sem endereço: 400
                    .andExpect(status().isBadRequest());
        }
        pedir("3.3.3.3", "11944443333", null).andExpect(status().isCreated());
    }

    @Test
    void tetoDaLojaSeguraMesmoComIpETelefoneDiferentes() throws Exception {
        for (int i = 0; i < 4; i++) {
            pedir("10.0.0." + i, "1191111000" + i, null).andExpect(status().isCreated());
        }
        pedir("10.0.0.99", "11922220000", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(containsString("muitos pedidos agora")));
    }

    @Test
    void ipVemDoCloudflareQuandoPresente() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos")
                            .header("CF-Connecting-IP", "200.1.1.1")
                            .with(r -> { r.setRemoteAddr("10.9.9." + Math.random()); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content(corpo("1196666000" + i)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos")
                        .header("CF-Connecting-IP", "200.1.1.1")
                        .contentType(MediaType.APPLICATION_JSON).content(corpo("11966660009")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(containsString("conexão")));
    }

    private ResultActions pedir(String ip, String telefone, String chave) throws Exception {
        var req = post("/api/public/lojas/" + loja.getSlug() + "/pedidos")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(corpo(telefone));
        if (chave != null) req.header("Idempotency-Key", chave);
        return mvc.perform(req);
    }

    private String corpo(String telefone) {
        return "{\"tipoEntrega\":\"RETIRADA\",\"metodoPagamento\":\"CARTAO_NA_ENTREGA\","
                + "\"cliente\":{\"nome\":\"Ana\",\"telefone\":\"" + telefone + "\"},"
                + "\"itens\":[{\"produtoId\":\"" + cafe + "\",\"quantidade\":1}]}";
    }
}
