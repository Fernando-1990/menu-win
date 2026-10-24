package br.com.menuwin.pagamento;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pix de mentira para homologar o fluxo sem conta no Mercado Pago (menuwin.pix.simulado=true).
 * Substitui o MercadoPagoGateway por inteiro; a aprovação vem de SimuladorPixController, que usa
 * o MESMO PixService.processarNotificacao do webhook real.
 *
 * TRAVA: só sobe nos profiles dev ou test. Em qualquer outro, a API não inicia.
 * Estado em memória: reiniciar a API perde as cobranças simuladas abertas.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "menuwin.pix.simulado", havingValue = "true")
public class SimuladorPixGateway implements GatewayPagamento {

    static final String MARCA = "SIMULADO";

    private final Map<String, SituacaoPagamento> cobrancas = new ConcurrentHashMap<>();
    /** Começa no relógio: ids não repetem entre reinícios (UNIQUE gateway + gateway_id). */
    private final AtomicLong sequencia = new AtomicLong(System.currentTimeMillis());

    public SimuladorPixGateway(Environment env) {
        if (!env.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("menuwin.pix.simulado=true só é permitido nos profiles dev ou test");
        }
        log.warn("PIX SIMULADO ATIVO: nenhuma cobrança real é criada no Mercado Pago");
    }

    @Override
    public String nome() {
        return CredencialGateway.MERCADO_PAGO;
    }

    /** Qualquer texto serve: no modo simulado o token não vai a lugar nenhum. */
    @Override
    public void validarCredencial(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new GatewayException("Token vazio", true, null);
        }
    }

    @Override
    public CobrancaPix criarPix(String accessToken, NovaCobrancaPix c) {
        String id = String.valueOf(sequencia.incrementAndGet());
        cobrancas.put(id, new SituacaoPagamento(id, StatusPagamento.PENDENTE, c.referencia().toString(), c.valor()));
        String copiaECola = "00020126580014BR.GOV.BCB.PIX-" + MARCA + "-" + id + "-NAO-PAGAR";
        return new CobrancaPix(id, copiaECola, null, c.expiraEm());
    }

    @Override
    public SituacaoPagamento consultar(String accessToken, String gatewayId) {
        SituacaoPagamento s = cobrancas.get(gatewayId);
        if (s == null) {
            throw new GatewayException("Cobrança simulada desconhecida (a API foi reiniciada?)", false, null);
        }
        return s;
    }

    /** O que o "banco" faria: muda o status da cobrança. Quem avisa o sistema é o controller. */
    void definirStatus(String gatewayId, StatusPagamento status) {
        if (cobrancas.computeIfPresent(gatewayId, (id, s) -> new SituacaoPagamento(id, status, s.referencia(), s.valor())) == null) {
            throw new GatewayException("Cobrança simulada desconhecida (a API foi reiniciada?)", false, null);
        }
    }
}
