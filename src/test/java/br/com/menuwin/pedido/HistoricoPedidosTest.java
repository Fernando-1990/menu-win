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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Histórico do painel. Cenário (todos R$ 10,00, criados hoje, nesta ordem):
 *  A entregue e pago no caixa · B entregue sem pagar · C cancelado · D em andamento ·
 *  E em andamento, do mesmo telefone de A mas com outro nome.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class HistoricoPedidosTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String URL = "/api/painel/historico/pedidos";

    @Autowired MockMvc mvc;
    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Loja loja;
    UUID produto;
    String dono, gerente, cozinha;
    String a, b, c, d, e;

    @BeforeEach
    void montar() throws Exception {
        loja = novaLoja();
        produto = novoProduto(loja);
        dono = token(loja, Papel.DONO, "Dona Rosa");
        gerente = token(loja, Papel.GERENTE, "Gerente Paulo");
        cozinha = token(loja, Papel.COZINHA, "Cozinheira Bia");

        a = pedir(loja, "Maria", "11911112222");
        for (String s : List.of("EM_PREPARO", "PRONTO", "ENTREGUE")) mudar(a, s, null, cozinha);
        mvc.perform(post("/api/painel/pedidos/" + a + "/pagamento/recebido").header("Authorization", "Bearer " + cozinha))
                .andExpect(status().isOk());
        b = pedir(loja, "João", "11933334444");
        mudar(b, "ENTREGUE", null, cozinha);
        c = pedir(loja, "Ana", "11955556666");
        mudar(c, "CANCELADO", "Cliente desistiu", gerente);
        d = pedir(loja, "Pedro", "11977778888");
        e = pedir(loja, "Maria Silva", "11911112222");   // mesmo telefone de A, outro nome
    }

    @Test
    void listaEResumoDoDia() throws Exception {
        listar(dono, "")
                .andExpect(jsonPath("$.pedidos", hasSize(5)))
                .andExpect(jsonPath("$.pedidos[0].id").value(e))           // mais recente primeiro
                .andExpect(jsonPath("$.pedidos[4].id").value(a))
                .andExpect(jsonPath("$.totalFiltrado").value(5))
                .andExpect(jsonPath("$.temMais").value(false))
                .andExpect(jsonPath("$.de").value(LocalDate.now(Loja.FUSO).toString()))
                .andExpect(jsonPath("$.resumo.pedidos").value(5))
                .andExpect(jsonPath("$.resumo.cancelados").value(1))
                .andExpect(jsonPath("$.resumo.vendido").value(40.00))     // A, B, D, E
                .andExpect(jsonPath("$.resumo.recebido").value(10.00))    // só A
                .andExpect(jsonPath("$.resumo.aReceber").value(30.00));   // B, D, E (C cancelado não conta)
    }

    @Test
    void filtros() throws Exception {
        assertThat(ids(listar(dono, "status=ENTREGUE"))).containsExactly(b, a);
        assertThat(ids(listar(dono, "status=CANCELADO"))).containsExactly(c);
        assertThat(ids(listar(dono, "status=RECEBIDO&status=EM_PREPARO"))).containsExactly(e, d);
        assertThat(ids(listar(dono, "pagamentoPendente=true"))).containsExactly(e, d, b);
        // o resumo é do período inteiro, não muda com o filtro de situação
        listar(dono, "status=CANCELADO").andExpect(jsonPath("$.resumo.pedidos").value(5));
    }

    @Test
    void periodo() throws Exception {
        LocalDate hoje = LocalDate.now(Loja.FUSO);
        listar(dono, "de=" + hoje.minusDays(7) + "&ate=" + hoje.minusDays(1))
                .andExpect(jsonPath("$.pedidos", hasSize(0)))
                .andExpect(jsonPath("$.resumo.pedidos").value(0))
                .andExpect(jsonPath("$.resumo.vendido").value(0));
        listar(dono, "de=" + hoje.minusDays(30) + "&ate=" + hoje).andExpect(jsonPath("$.pedidos", hasSize(5)));
        mvc.perform(get(URL + "?de=" + hoje + "&ate=" + hoje.minusDays(1)).header("Authorization", "Bearer " + dono))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A data final é antes da inicial"));
        mvc.perform(get(URL + "?de=" + hoje.minusDays(400) + "&ate=" + hoje).header("Authorization", "Bearer " + dono))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detalheComLinhaDoTempoENomeDoMomento() throws Exception {
        mvc.perform(get(URL + "/" + a).header("Authorization", "Bearer " + gerente))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteNome").value("Maria"))      // E mudou o cadastro para "Maria Silva"
                .andExpect(jsonPath("$.clienteTelefone").value("5511911112222"))
                .andExpect(jsonPath("$.itens[0].nome").value("Bolo"))
                .andExpect(jsonPath("$.total").value(10.00))
                .andExpect(jsonPath("$.pagamento.metodo").value("NO_CAIXA"))
                .andExpect(jsonPath("$.pagamento.status").value("APROVADO"))
                .andExpect(jsonPath("$.pagamento.pagoEm").exists())
                .andExpect(jsonPath("$.linhaDoTempo[*].status").value(org.hamcrest.Matchers.contains("RECEBIDO", "EM_PREPARO", "PRONTO", "ENTREGUE")))
                .andExpect(jsonPath("$.linhaDoTempo[0].quem").doesNotExist())   // pedido feito pelo cliente
                .andExpect(jsonPath("$.linhaDoTempo[1].quem").value("Cozinheira Bia"));

        mvc.perform(get(URL + "/" + c).header("Authorization", "Bearer " + dono))
                .andExpect(jsonPath("$.motivoCancelamento").value("Cliente desistiu"))
                .andExpect(jsonPath("$.linhaDoTempo[1].quem").value("Gerente Paulo"));
        mvc.perform(get(URL + "/" + e).header("Authorization", "Bearer " + dono))
                .andExpect(jsonPath("$.clienteNome").value("Maria Silva"));
    }

    @Test
    void soDonoEGerenteEOutraLojaNaoVe() throws Exception {
        listar(gerente, "").andExpect(status().isOk());
        mvc.perform(get(URL).header("Authorization", "Bearer " + cozinha)).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/" + a).header("Authorization", "Bearer " + cozinha)).andExpect(status().isForbidden());

        Loja outra = novaLoja();
        String donoOutra = token(outra, Papel.DONO, "Outro dono");
        listar(donoOutra, "").andExpect(jsonPath("$.pedidos", hasSize(0))).andExpect(jsonPath("$.resumo.pedidos").value(0));
        mvc.perform(get(URL + "/" + a).header("Authorization", "Bearer " + donoOutra)).andExpect(status().isNotFound());
    }

    @Test
    void paginacao() throws Exception {
        for (int i = 0; i < 27; i++) pedir(loja, "Cliente " + i, "119000000" + String.format("%02d", i));   // 5 + 27 = 32
        listar(dono, "").andExpect(jsonPath("$.pedidos", hasSize(30))).andExpect(jsonPath("$.temMais").value(true))
                .andExpect(jsonPath("$.totalFiltrado").value(32));
        listar(dono, "pagina=1").andExpect(jsonPath("$.pedidos", hasSize(2))).andExpect(jsonPath("$.temMais").value(false))
                .andExpect(jsonPath("$.pedidos[1].id").value(a));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions listar(String token, String query) throws Exception {
        return mvc.perform(get(URL + (query.isEmpty() ? "" : "?" + query)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private static List<String> ids(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.pedidos[*].id");
    }

    private String pedir(Loja l, String nome, String telefone) throws Exception {
        String body = mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoEntrega\":\"RETIRADA\",\"metodoPagamento\":\"NO_CAIXA\","
                                + "\"cliente\":{\"nome\":\"" + nome + "\",\"telefone\":\"" + telefone + "\"},"
                                + "\"itens\":[{\"produtoId\":\"" + produto + "\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void mudar(String pedidoId, String status, String motivo, String token) throws Exception {
        mvc.perform(patch("/api/painel/pedidos/" + pedidoId + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"" + (motivo == null ? "" : ",\"motivo\":\"" + motivo + "\"") + "}"))
                .andExpect(status().isOk());
    }

    private Loja novaLoja() {
        Loja l = new Loja();
        l.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        l.setNome("Padaria");
        l.setAceitaPagarNoCaixa(true);
        return lojaRepository.save(l);
    }

    private UUID novoProduto(Loja l) {
        TenantContext.set(l.getId());
        try {
            Categoria cat = new Categoria();
            cat.setNome("Bolos");
            categoriaRepository.save(cat);
            Produto p = new Produto();
            p.setCategoria(cat);
            p.setNome("Bolo");
            p.setPreco(new BigDecimal("10.00"));
            return produtoRepository.save(p).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private String token(Loja l, Papel papel, String nome) throws Exception {
        Usuario u = new Usuario();
        u.setLojaId(l.getId());
        u.setNome(nome);
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
