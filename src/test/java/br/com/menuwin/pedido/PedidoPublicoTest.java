package br.com.menuwin.pedido;

import br.com.menuwin.cardapio.Categoria;
import br.com.menuwin.cardapio.CategoriaRepository;
import br.com.menuwin.cardapio.GrupoAdicional;
import br.com.menuwin.cardapio.GrupoAdicionalRepository;
import br.com.menuwin.cardapio.OpcaoAdicional;
import br.com.menuwin.cardapio.Produto;
import br.com.menuwin.cardapio.ProdutoRepository;
import br.com.menuwin.loja.HorarioFuncionamento;
import br.com.menuwin.loja.Loja;
import br.com.menuwin.loja.LojaRepository;
import br.com.menuwin.tenant.TenantContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cardápio montado em cada teste:
 *   X-Burguer R$ 20,00 (promo R$ 18,00)
 *     "Pão"    min 1 max 1: Francês R$ 0, Australiano R$ 2
 *     "Extras" min 0 max 3: Bacon R$ 3, Ovo R$ 2 (indisponível)
 *   Suco R$ 8,00 (acabou hoje)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class PedidoPublicoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired GrupoAdicionalRepository grupoRepository;

    Loja loja;
    UUID xBurguer, suco, frances, australiano, bacon, ovo;

    @BeforeEach
    void montarCardapio() {
        loja = new Loja();
        loja.setSlug("loja-" + UUID.randomUUID().toString().substring(0, 8));
        loja.setNome("Lanchonete");
        loja.setAceitaDelivery(true);
        loja.setTaxaEntregaPadrao(new BigDecimal("5.00"));
        loja = lojaRepository.save(loja);

        TenantContext.set(loja.getId());
        Categoria lanches = new Categoria();
        lanches.setNome("Lanches");
        categoriaRepository.save(lanches);

        GrupoAdicional pao = grupo("Pão", 1, 1);
        opcao(pao, "Francês", "0.00", true);
        opcao(pao, "Australiano", "2.00", true);
        GrupoAdicional extras = grupo("Extras", 0, 3);
        opcao(extras, "Bacon", "3.00", true);
        opcao(extras, "Ovo", "2.00", false);
        grupoRepository.saveAll(List.of(pao, extras));   // ids das opções só existem depois de salvar
        frances = id(pao, "Francês");
        australiano = id(pao, "Australiano");
        bacon = id(extras, "Bacon");
        ovo = id(extras, "Ovo");

        xBurguer = produto(lanches, "X-Burguer", "20.00", "18.00", true, pao, extras);
        suco = produto(lanches, "Suco", "8.00", null, false);
        TenantContext.clear();
    }

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void retiradaComAdicionaisCalculaNoServidorENumeraEmSequencia() throws Exception {
        // (18 promo + 2 australiano + 2x3 bacon) x 2 = 52
        String item = item(xBurguer, 2, adicional(australiano, 1), adicional(bacon, 2));
        String body = pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value(1))
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.subtotal").value(52.00))
                .andExpect(jsonPath("$.taxaEntrega").value(0))
                .andExpect(jsonPath("$.total").value(52.00))
                .andExpect(jsonPath("$.itens[0].precoUnitario").value(18.00))
                .andExpect(jsonPath("$.itens[0].adicionais", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        UUID pedidoId = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(jdbc.queryForObject("select count(*) from historico_status_pedido where pedido_id = ? and status = 'RECEBIDO'",
                Integer.class, pedidoId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select valor from pagamentos where pedido_id = ? and status = 'PENDENTE'",
                BigDecimal.class, pedidoId)).isEqualByComparingTo("52.00");

        // mesmo telefone em outro formato = mesmo cliente; número segue a sequência
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item(xBurguer, 1, adicional(frances, 1)))
                .replace("(11) 98888-7777", "5511988887777"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value(2));
        assertThat(jdbc.queryForObject("select count(*) from clientes where loja_id = ? and telefone = '5511988887777'",
                Integer.class, loja.getId())).isEqualTo(1);
    }

    @Test
    void mudarOCardapioDepoisNaoAlteraOPedido() throws Exception {
        String body = pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item(xBurguer, 1, adicional(frances, 1))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID pedidoId = UUID.fromString(JsonPath.read(body, "$.id"));

        jdbc.update("update produtos set nome = 'X-Novo', preco = 99, preco_promocional = null where id = ?", xBurguer);

        var item = jdbc.queryForMap("select nome_produto, preco_unitario from itens_pedido where pedido_id = ?", pedidoId);
        assertThat(item.get("nome_produto")).isEqualTo("X-Burguer");
        assertThat((BigDecimal) item.get("preco_unitario")).isEqualByComparingTo("18.00");
    }

    @Test
    void deliveryCobraTaxaEExigeEndereco() throws Exception {
        String item = item(xBurguer, 1, adicional(frances, 1));
        pedir(pedido("DELIVERY", ENDERECO, "DINHEIRO_NA_ENTREGA", "\"trocoPara\":\"50.00\"", item))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxaEntrega").value(5.00))
                .andExpect(jsonPath("$.total").value(23.00));

        pedir(pedido("DELIVERY", null, "DINHEIRO_NA_ENTREGA", null, item))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Informe o endereço de entrega"));
    }

    @Test
    void validacaoDosAdicionais() throws Exception {
        String semPao = item(xBurguer, 1, adicional(bacon, 1));
        esperarErro(semPao, "X-Burguer: escolha pelo menos 1 em \"Pão\"");

        String doisPaes = item(xBurguer, 1, adicional(frances, 1), adicional(australiano, 1));
        esperarErro(doisPaes, "X-Burguer: no máximo 1 em \"Pão\"");

        String baconDemais = item(xBurguer, 1, adicional(frances, 1), adicional(bacon, 4));
        esperarErro(baconDemais, "X-Burguer: no máximo 3 em \"Extras\"");

        String ovoIndisponivel = item(xBurguer, 1, adicional(frances, 1), adicional(ovo, 1));
        esperarErro(ovoIndisponivel, "X-Burguer: Ovo acabou por hoje");

        String repetido = item(xBurguer, 1, adicional(frances, 1), adicional(bacon, 1), adicional(bacon, 1));
        esperarErro(repetido, "X-Burguer: adicional repetido; use a quantidade");

        String adicionalNoSuco = item(suco, 1, adicional(bacon, 1));
        esperarErro(adicionalNoSuco, "Suco acabou por hoje");   // produto indisponível vem antes

        String opcaoInventada = item(xBurguer, 1, adicional(frances, 1), adicional(UUID.randomUUID(), 1));
        esperarErro(opcaoInventada, "X-Burguer: adicional não pertence a este produto");

        esperarErro(item(UUID.randomUUID(), 1), "Produto não encontrado no cardápio");
    }

    @Test
    void produtoDeOutraLojaNaoEntra() throws Exception {
        Loja outra = new Loja();
        outra.setSlug("outra-" + UUID.randomUUID().toString().substring(0, 8));
        outra.setNome("Outra");
        outra = lojaRepository.save(outra);

        // o X-Burguer existe, mas é da primeira loja
        mvc.perform(post("/api/public/lojas/" + outra.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item(xBurguer, 1, adicional(frances, 1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Produto não encontrado no cardápio"));
    }

    @Test
    void regrasDaLojaEDoPagamento() throws Exception {
        String item = item(xBurguer, 1, adicional(frances, 1));   // total 18

        pedir(pedido("RETIRADA", null, "PIX", null, item)).andExpect(status().isBadRequest());
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", "\"trocoPara\":\"50.00\"", item))
                .andExpect(jsonPath("$.detail").value("Troco só vale para pagamento em dinheiro"));
        pedir(pedido("RETIRADA", null, "DINHEIRO_NA_ENTREGA", "\"trocoPara\":\"10.00\"", item))
                .andExpect(jsonPath("$.detail").value("O valor para troco é menor que o total do pedido"));
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", "\"totalEsperado\":\"17.00\"", item))
                .andExpect(status().isConflict());
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", "\"totalEsperado\":\"18.00\"", item))
                .andExpect(status().isCreated());

        jdbc.update("update lojas set pedido_minimo = 30 where id = ?", loja.getId());
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item))
                .andExpect(jsonPath("$.detail").value("O pedido mínimo desta loja é R$ 30.00"));

        jdbc.update("update lojas set pedido_minimo = 0, aceita_retirada = false where id = ?", loja.getId());
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item))
                .andExpect(jsonPath("$.detail").value("Esta loja não aceita retirada"));

        // fechada: só abre amanhã
        jdbc.update("update lojas set aceita_retirada = true where id = ?", loja.getId());
        int amanha = LocalDate.now(Loja.FUSO).plusDays(1).getDayOfWeek().getValue();
        jdbc.update("insert into horarios_funcionamento (loja_id, dia_semana, abre, fecha) values (?, ?, ?, ?)",
                loja.getId(), amanha, LocalTime.of(10, 0), LocalTime.of(11, 0));
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A loja está fechada agora"));
        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.loja.aberta").value(false));
    }

    @Test
    void pedidosSimultaneosNaoRepetemNumero() throws Exception {
        String corpo = pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item(xBurguer, 1, adicional(frances, 1)));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tarefas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tarefas.add(() -> mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos")
                                .contentType(MediaType.APPLICATION_JSON).content(corpo))
                        .andReturn().getResponse().getStatus());
            }
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tarefas)) {
                status.add(f.get());
            }
            assertThat(status).containsOnly(201);
        } finally {
            pool.shutdown();
        }
        List<Integer> numeros = jdbc.queryForList("select numero from pedidos where loja_id = ? order by numero",
                Integer.class, loja.getId());
        assertThat(numeros).hasSize(20).doesNotHaveDuplicates().startsWith(1).endsWith(20);
    }

    @Test
    void cardapioPublicoMostraAdicionaisEDadosDoCheckout() throws Exception {
        mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/cardapio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loja.aberta").value(true))
                .andExpect(jsonPath("$.loja.aceitaDelivery").value(true))
                .andExpect(jsonPath("$.loja.taxaEntrega").value(5.00))
                .andExpect(jsonPath("$.categorias[0].produtos[0].nome").value("X-Burguer"))
                .andExpect(jsonPath("$.categorias[0].produtos[0].grupos[0].nome").value("Pão"))
                .andExpect(jsonPath("$.categorias[0].produtos[0].grupos[1].opcoes", hasSize(1)))   // Ovo indisponível some
                .andExpect(jsonPath("$.categorias[0].produtos[1].disponivel").value(false));
    }

    // ---------------------------------------------------------------- helpers

    private static final String ENDERECO = """
            {"logradouro":"Rua das Flores","numero":"100","bairro":"Centro","cidade":"São Paulo","uf":"sp","cep":"01001-000"}""";

    private ResultActions pedir(String json) throws Exception {
        return mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void esperarErro(String item, String mensagem) throws Exception {
        pedir(pedido("RETIRADA", null, "CARTAO_NA_ENTREGA", null, item))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(mensagem));
    }

    private static String pedido(String tipo, String endereco, String metodo, String extra, String... itens) {
        return "{\"tipoEntrega\":\"" + tipo + "\","
                + "\"cliente\":{\"nome\":\"Maria\",\"telefone\":\"(11) 98888-7777\"},"
                + (endereco == null ? "" : "\"endereco\":" + endereco + ",")
                + "\"metodoPagamento\":\"" + metodo + "\","
                + (extra == null ? "" : extra + ",")
                + "\"itens\":[" + String.join(",", itens) + "]}";
    }

    private static String item(UUID produtoId, int quantidade, String... adicionais) {
        return "{\"produtoId\":\"" + produtoId + "\",\"quantidade\":" + quantidade
                + ",\"adicionais\":[" + String.join(",", adicionais) + "]}";
    }

    private static String adicional(UUID opcaoId, int quantidade) {
        return "{\"opcaoId\":\"" + opcaoId + "\",\"quantidade\":" + quantidade + "}";
    }

    private static GrupoAdicional grupo(String nome, int min, int max) {
        GrupoAdicional g = new GrupoAdicional();
        g.setNome(nome);
        g.setMinimo(min);
        g.setMaximo(max);
        return g;
    }

    private static void opcao(GrupoAdicional g, String nome, String preco, boolean disponivel) {
        OpcaoAdicional o = new OpcaoAdicional();
        o.setGrupo(g);
        o.setNome(nome);
        o.setPreco(new BigDecimal(preco));
        o.setDisponivel(disponivel);
        o.setOrdem(g.getOpcoes().size());
        g.getOpcoes().add(o);
    }

    private static UUID id(GrupoAdicional g, String nomeOpcao) {
        return g.getOpcoes().stream().filter(o -> o.getNome().equals(nomeOpcao)).findFirst().orElseThrow().getId();
    }

    private UUID produto(Categoria c, String nome, String preco, String promo, boolean disponivel, GrupoAdicional... grupos) {
        Produto p = new Produto();
        p.setCategoria(c);
        p.setNome(nome);
        p.setPreco(new BigDecimal(preco));
        p.setPrecoPromocional(promo == null ? null : new BigDecimal(promo));
        p.setDisponivel(disponivel);
        p.setOrdem(produtoRepository.count() > 0 ? 1 : 0);
        p.getGrupos().addAll(List.of(grupos));
        return produtoRepository.save(p).getId();
    }
}
