package br.com.menuwin.pagamento;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A trava: o Pix de mentira nunca pode subir em produção. */
class SimuladorPixGatewayTest {

    @Test
    void recusaSubirForaDeDevOuTest() {
        MockEnvironment producao = new MockEnvironment();
        producao.setActiveProfiles("prod");
        assertThatThrownBy(() -> new SimuladorPixGateway(producao))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("só é permitido nos profiles dev ou test");

        assertThatThrownBy(() -> new SimuladorPixGateway(new MockEnvironment()))   // sem profile nenhum
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sobeEmDevETest() {
        for (String profile : new String[] {"dev", "test"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThatCode(() -> new SimuladorPixGateway(env)).doesNotThrowAnyException();
        }
    }
}
