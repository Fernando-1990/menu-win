package br.com.menuwin.pedido;

import br.com.menuwin.pedido.LimitePedidos.MuitosPedidos;
import br.com.menuwin.pedido.LimitesPedidoProperties.Janela;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Janela deslizante com relógio controlado. */
class LimitePedidosTest {

    static final Duration DEZ_MIN = Duration.ofMinutes(10);
    final UUID loja = UUID.randomUUID();
    final RelogioFalso relogio = new RelogioFalso();
    final LimitePedidos limite = new LimitePedidos(
            new LimitesPedidoProperties(new Janela(3, DEZ_MIN), new Janela(2, DEZ_MIN), new Janela(5, DEZ_MIN)), relogio);

    @Test
    void telefoneNoLimiteRecebe429ComRetryAfterEDepoisDaJanelaLibera() {
        pedido("1.1.1.1", "(11) 98888-7777");
        relogio.andar(Duration.ofMinutes(4));
        pedido("2.2.2.2", "11988887777");   // mesmo telefone, outro formato

        assertThatThrownBy(() -> limite.verificar(loja, "3.3.3.3", "+55 11 98888-7777"))
                .isInstanceOfSatisfying(MuitosPedidos.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    assertThat(e.getReason()).contains("telefone");
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("360");   // 6 min até o 1º sair
                });

        relogio.andar(Duration.ofMinutes(6));   // o primeiro saiu da janela
        assertThatCode(() -> limite.verificar(loja, "3.3.3.3", "11988887777")).doesNotThrowAnyException();
    }

    @Test
    void ipNoLimite() {
        pedido("1.1.1.1", "11911110001");
        pedido("1.1.1.1", "11911110002");
        pedido("1.1.1.1", "11911110003");
        assertThatThrownBy(() -> limite.verificar(loja, "1.1.1.1", "11911110004"))
                .isInstanceOfSatisfying(MuitosPedidos.class, e -> assertThat(e.getReason()).contains("conexão"));
        assertThatCode(() -> limite.verificar(loja, "9.9.9.9", "11911110004")).doesNotThrowAnyException();
    }

    @Test
    void tetoDaLojaNaoSeContornaTrocandoIpETelefone() {
        for (int i = 0; i < 5; i++) {
            pedido("10.0.0." + i, "1192222000" + i);
        }
        assertThatThrownBy(() -> limite.verificar(loja, "10.0.0.99", "11933330000"))
                .isInstanceOfSatisfying(MuitosPedidos.class, e -> assertThat(e.getReason()).contains("loja"));
        // outra loja não é afetada
        assertThatCode(() -> limite.verificar(UUID.randomUUID(), "10.0.0.99", "11933330000")).doesNotThrowAnyException();
    }

    @Test
    void verificarSemRegistrarNaoGastaACota() {
        for (int i = 0; i < 10; i++) {
            limite.verificar(loja, "1.1.1.1", "11988887777");   // ex.: pedido recusado pela validação
        }
        pedido("1.1.1.1", "11988887777");
        assertThatCode(() -> limite.verificar(loja, "1.1.1.1", "11988887777")).doesNotThrowAnyException();
    }

    @Test
    void limparTiraOQueSaiuDaJanela() {
        pedido("1.1.1.1", "11988887777");
        pedido("1.1.1.1", "11988887777");
        relogio.andar(Duration.ofMinutes(11));
        limite.limpar();
        assertThatCode(() -> limite.verificar(loja, "1.1.1.1", "11988887777")).doesNotThrowAnyException();
    }

    private void pedido(String ip, String telefone) {
        limite.verificar(loja, ip, telefone);
        limite.registrar(loja, ip, telefone);
    }

    static final class RelogioFalso extends Clock {
        private Instant agora = Instant.parse("2026-11-24T12:00:00Z");

        void andar(Duration d) {
            agora = agora.plus(d);
        }

        @Override public Instant instant() { return agora; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
