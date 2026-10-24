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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pix de ponta a ponta com o gateway falso (o HTTP do Mercado Pago é testado em MercadoPagoGatewayTest):
 * credencial no painel, pedido Pix, webhook aprovando/expirando, e webhooks que NÃO devem mudar nada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(PixFluxoTest.GatewayFalsoConfig.class)
class PixFluxoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class GatewayFalsoConfig {
        @Bean
        @Primary
        GatewayFalso gatewayFalso() {
            return new GatewayFalso();
        }
    }

    /** Guarda o que recebeu e devolve o status que o teste mandar. */
    static class GatewayFalso implements GatewayPagamento {
        final Map<String, SituacaoPagamento> situacoes = new ConcurrentHashMap<>();
        final AtomicLong sequencia = new AtomicLong(1000);
        volatile boolean falharNaCriacao;
        volatile String ultimoToken;
        volatile NovaCobrancaPix ultimaCobranca;

        @Override public String nome() { return CredencialGateway.MERCADO_PAGO; }

        @Override
        public void validarCredencial(String token) {
            if (!token.startsWith("TEST-") && !token.startsWith("APP_USR-")) {
                throw new GatewayException("401", true, null);
            }
        }

        @Override
        public CobrancaPix criarPix(String token, NovaCobrancaPix c) {
            if (falharNaCriacao) throw new GatewayException("500", false, null);
            ultimoToken = token;
            ultimaCobranca = c;
            String id = String.valueOf(sequencia.incrementAndGet());
            situacoes.put(id, new SituacaoPagamento(id, StatusPagamento.PENDENTE, c.referencia().toString(), c.valor()));
            return new CobrancaPix(id, "00020126-pix-copia-e-cola-" + id, "iVBORw0KGgo", c.expiraEm());
        }

        @Override
        public SituacaoPagamento consultar(String token, String gatewayId) {
            return situacoes.get(gatewayId);
        }

        void mudar(String id, StatusPagamento status) {
            situacoes.computeIfPresent(id, (k, s) -> new SituacaoPagamento(k, status, s.referencia(), s.valor()));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GatewayFalso gateway;
    @Autowired LojaRepository lojaRepository;
    @Autowired CategoriaRepository categoriaRepository;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Loja loja;
    UUID produto;   // R$ 25,00, sem adicionais

    @BeforeEach
    void montar() throws Exception {
        gateway.situacoes.clear();
        gateway.falharNaCriacao = false;
        loja = novaLoja();
        produto = novoProduto(loja);
        configurarCredencial(loja, "TEST-123456-token-da-loja").andExpect(status().isOk());
    }

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    @Test
    void credencialSoParaDonoCifradaENuncaDevolvida() throws Exception {
        Loja outra = novaLoja();
        String dono = token(outra, Papel.DONO);
        String gerente = token(outra, Papel.GERENTE);

        mvc.perform(get("/api/painel/pagamentos/mercadopago").header("Authorization", "Bearer " + dono))
                .andExpect(jsonPath("$.configurado").value(false));
        mvc.perform(put("/api/painel/pagamentos/mercadopago").header("Authorization", "Bearer " + gerente)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"TEST-1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/painel/pagamentos/mercadopago").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"token-invalido\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Access token recusado pelo Mercado Pago"));
        mvc.perform(put("/api/painel/pagamentos/mercadopago").header("Authorization", "Bearer " + dono)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"APP_USR-999-segredo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurado").value(true))
                .andExpect(jsonPath("$.modoTeste").value(false))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        String gravado = jdbc.queryForObject("select access_token_cifrado from credenciais_gateway where loja_id = ?",
                String.class, outra.getId());
        assertThat(gravado).doesNotContain("APP_USR").doesNotContain("segredo");

        mvc.perform(get("/api/public/lojas/" + outra.getSlug() + "/cardapio"))
                .andExpect(jsonPath("$.loja.aceitaPix").value(true));
    }

    @Test
    void pixAprovadoVaiParaACozinhaUmaVezSo() throws Exception {
        String body = pedirPix(loja).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"))
                .andExpect(jsonPath("$.pix.copiaECola").exists())
                .andExpect(jsonPath("$.pix.qrCodeBase64").value("iVBORw0KGgo"))
                .andReturn().getResponse().getContentAsString();
        String pedidoId = JsonPath.read(body, "$.id");
        String mpId = gatewayIdDoPedido(pedidoId);

        // o gateway recebeu o token da loja decifrado, o valor certo e a URL de webhook da loja
        assertThat(gateway.ultimoToken).isEqualTo("TEST-123456-token-da-loja");
        assertThat(gateway.ultimaCobranca.valor()).isEqualByComparingTo("25.00");
        assertThat(gateway.ultimaCobranca.urlNotificacao())
                .isEqualTo("https://api.teste.local/api/public/lojas/" + loja.getSlug() + "/webhooks/mercadopago");

        consultar(pedidoId).andExpect(jsonPath("$.statusPagamento").value("PENDENTE"))
                .andExpect(jsonPath("$.pixCopiaECola").exists());

        webhook(loja, mpId);   // ainda pendente: nada muda
        consultar(pedidoId).andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));

        gateway.mudar(mpId, StatusPagamento.APROVADO);
        webhook(loja, mpId).andExpect(status().isOk());
        webhook(loja, mpId).andExpect(status().isOk());   // o MP reenvia; tem que ser idempotente
        consultar(pedidoId)
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.statusPagamento").value("APROVADO"))
                .andExpect(jsonPath("$.pixCopiaECola").doesNotExist());
        assertThat(historico(pedidoId)).isEqualTo(2);   // AGUARDANDO_PAGAMENTO e RECEBIDO, sem duplicar

        // depois de aprovado, um "cancelled" fora de ordem não desfaz nada
        gateway.mudar(mpId, StatusPagamento.EXPIRADO);
        webhook(loja, mpId);
        consultar(pedidoId).andExpect(jsonPath("$.status").value("RECEBIDO"));
    }

    @Test
    void pixExpiradoCancelaOPedido() throws Exception {
        String pedidoId = JsonPath.read(pedirPix(loja).andReturn().getResponse().getContentAsString(), "$.id");
        String mpId = gatewayIdDoPedido(pedidoId);

        gateway.mudar(mpId, StatusPagamento.EXPIRADO);
        webhookFormatoIpn(loja, mpId).andExpect(status().isOk());   // formato antigo (?topic=payment&id=)
        consultar(pedidoId)
                .andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.statusPagamento").value("EXPIRADO"))
                .andExpect(jsonPath("$.motivoCancelamento").value("Pix não pago no prazo"));
    }

    @Test
    void webhookNaoConfiavelNaoMudaNada() throws Exception {
        String pedidoId = JsonPath.read(pedirPix(loja).andReturn().getResponse().getContentAsString(), "$.id");
        String mpId = gatewayIdDoPedido(pedidoId);

        // valor no MP diferente do pedido
        var s = gateway.situacoes.get(mpId);
        gateway.situacoes.put(mpId, new GatewayPagamento.SituacaoPagamento(mpId, StatusPagamento.APROVADO,
                s.referencia(), new BigDecimal("0.01")));
        webhook(loja, mpId).andExpect(status().isOk());
        consultar(pedidoId).andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));

        // pagamento aprovado na conta da loja B, apontando para o pedido da loja A
        Loja lojaB = novaLoja();
        configurarCredencial(lojaB, "TEST-loja-b").andExpect(status().isOk());
        gateway.situacoes.put("777", new GatewayPagamento.SituacaoPagamento("777", StatusPagamento.APROVADO,
                s.referencia(), s.valor()));
        webhook(lojaB, "777").andExpect(status().isOk());
        consultar(pedidoId).andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));

        // tipo que não é pagamento e id que não existe no MP
        mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/webhooks/mercadopago")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"merchant_order\",\"data\":{\"id\":\"" + mpId + "\"}}"))
                .andExpect(status().isOk());
        consultar(pedidoId).andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));
    }

    @Test
    void falhaNoGatewayCancelaOPedidoEAvisaOCliente() throws Exception {
        gateway.falharNaCriacao = true;
        pedirPix(loja)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Não foi possível gerar o Pix agora. Tente de novo ou escolha pagar na entrega."));

        var linha = jdbc.queryForMap("""
                select p.status as pedido, pg.status as pagamento from pedidos p join pagamentos pg on pg.pedido_id = p.id
                where p.loja_id = ?""", loja.getId());
        assertThat(linha.get("pedido")).isEqualTo("CANCELADO");
        assertThat(linha.get("pagamento")).isEqualTo("RECUSADO");
    }

    @Test
    void pixExigeEmailELojaConfigurada() throws Exception {
        mvc.perform(post("/api/public/lojas/" + loja.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content(corpoPix(produto, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Informe seu e-mail para pagar com Pix"));

        Loja semPix = novaLoja();
        UUID produtoSemPix = novoProduto(semPix);
        mvc.perform(post("/api/public/lojas/" + semPix.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                        .content(corpoPix(produtoSemPix, "maria@exemplo.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Esta loja não aceita Pix pelo cardápio"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions pedirPix(Loja l) throws Exception {
        return mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/pedidos").contentType(MediaType.APPLICATION_JSON)
                .content(corpoPix(produto, "maria@exemplo.com")));
    }

    private static String corpoPix(UUID produtoId, String email) {
        return "{\"tipoEntrega\":\"RETIRADA\",\"metodoPagamento\":\"PIX\","
                + "\"cliente\":{\"nome\":\"Maria\",\"telefone\":\"11988887777\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"") + "},"
                + "\"itens\":[{\"produtoId\":\"" + produtoId + "\"}]}";
    }

    private ResultActions webhook(Loja l, String mpId) throws Exception {
        return mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/webhooks/mercadopago?data.id=" + mpId + "&type=payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"payment.updated\",\"type\":\"payment\",\"data\":{\"id\":\"" + mpId + "\"}}"));
    }

    private ResultActions webhookFormatoIpn(Loja l, String mpId) throws Exception {
        return mvc.perform(post("/api/public/lojas/" + l.getSlug() + "/webhooks/mercadopago?topic=payment&id=" + mpId));
    }

    private ResultActions consultar(String pedidoId) throws Exception {
        return mvc.perform(get("/api/public/lojas/" + loja.getSlug() + "/pedidos/" + pedidoId)).andExpect(status().isOk());
    }

    private String gatewayIdDoPedido(String pedidoId) {
        return jdbc.queryForObject("select gateway_id from pagamentos where pedido_id = ?", String.class, UUID.fromString(pedidoId));
    }

    private int historico(String pedidoId) {
        return jdbc.queryForObject("select count(*) from historico_status_pedido where pedido_id = ?",
                Integer.class, UUID.fromString(pedidoId));
    }

    private ResultActions configurarCredencial(Loja l, String accessToken) throws Exception {
        return mvc.perform(put("/api/painel/pagamentos/mercadopago").header("Authorization", "Bearer " + token(l, Papel.DONO))
                .contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"" + accessToken + "\"}"));
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
            c.setNome("Pães");
            categoriaRepository.save(c);
            Produto p = new Produto();
            p.setCategoria(c);
            p.setNome("Bolo");
            p.setPreco(new BigDecimal("25.00"));
            return produtoRepository.save(p).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private String token(Loja l, Papel papel) throws Exception {
        Usuario u = new Usuario();
        u.setLojaId(l.getId());
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
