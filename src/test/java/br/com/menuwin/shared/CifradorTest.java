package br.com.menuwin.shared;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CifradorTest {

    private static final String CHAVE = Base64.getEncoder().encodeToString("chave-de-teste-com-32-bytes-ok!!".getBytes());
    private static final String OUTRA = Base64.getEncoder().encodeToString("outra-chave-de-teste-32-bytes!!!".getBytes());

    @Test
    void cifraEDecifra() {
        Cifrador c = new Cifrador(CHAVE);
        String gravado = c.cifrar("APP_USR-123-segredo");
        assertThat(gravado).doesNotContain("APP_USR").doesNotContain("segredo");
        assertThat(c.decifrar(gravado)).isEqualTo("APP_USR-123-segredo");
    }

    @Test
    void mesmoTextoGeraCifrasDiferentes() {
        Cifrador c = new Cifrador(CHAVE);
        assertThat(c.cifrar("igual")).isNotEqualTo(c.cifrar("igual"));   // IV aleatório
    }

    @Test
    void chaveErradaOuDadoAdulteradoFalham() {
        String gravado = new Cifrador(CHAVE).cifrar("segredo");
        assertThatThrownBy(() -> new Cifrador(OUTRA).decifrar(gravado)).isInstanceOf(IllegalStateException.class);

        byte[] bytes = Base64.getDecoder().decode(gravado);
        bytes[bytes.length - 1] ^= 1;
        String adulterado = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> new Cifrador(CHAVE).decifrar(adulterado)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chaveComTamanhoErradoNemSobe() {
        String curta = Base64.getEncoder().encodeToString("curta".getBytes());
        assertThatThrownBy(() -> new Cifrador(curta)).isInstanceOf(IllegalStateException.class);
    }
}
