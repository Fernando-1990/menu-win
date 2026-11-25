package br.com.menuwin.pedido;

import br.com.menuwin.cliente.Cliente;
import br.com.menuwin.pedido.LimitesPedidoProperties.Janela;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de pedidos públicos por IP, por telefone e por loja (janela deslizante).
 * verificar() antes de criar; registrar() só depois de criar: erro de validação não gasta a cota do cliente.
 * Duas requisições ao mesmo tempo podem passar juntas do limite por uma unidade; aceitável.
 *
 * EM MEMÓRIA: vale com UMA instância da API (como o SSE da cozinha). Reiniciar zera as contagens.
 */
@Slf4j
@Component
public class LimitePedidos {

    private final LimitesPedidoProperties limites;
    private final Clock relogio;
    private final Map<String, Deque<Instant>> registros = new ConcurrentHashMap<>();

    @Autowired
    public LimitePedidos(LimitesPedidoProperties limites) {
        this(limites, Clock.systemUTC());
    }

    LimitePedidos(LimitesPedidoProperties limites, Clock relogio) {
        this.limites = limites;
        this.relogio = relogio;
    }

    /** 429 com Retry-After se alguma das três contagens já chegou ao máximo. */
    public void verificar(UUID lojaId, String ip, String telefone) {
        Instant agora = relogio.instant();
        String tel = telefoneNormalizado(telefone);
        if (excedeu("loja:" + lojaId, limites.loja(), agora)) {
            log.warn("Loja {} atingiu o teto de pedidos públicos ({} em {})", lojaId, limites.loja().maximo(), limites.loja().periodo());
            throw new MuitosPedidos("A loja está recebendo muitos pedidos agora. Tente de novo em alguns minutos.",
                    espera("loja:" + lojaId, limites.loja(), agora));
        }
        if (tel != null && excedeu(chaveTelefone(lojaId, tel), limites.telefone(), agora)) {
            throw new MuitosPedidos("Muitos pedidos seguidos deste telefone. Aguarde alguns minutos e tente de novo.",
                    espera(chaveTelefone(lojaId, tel), limites.telefone(), agora));
        }
        if (ip != null && excedeu(chaveIp(lojaId, ip), limites.ip(), agora)) {
            throw new MuitosPedidos("Muitos pedidos seguidos desta conexão. Aguarde alguns minutos e tente de novo.",
                    espera(chaveIp(lojaId, ip), limites.ip(), agora));
        }
    }

    /** Conta um pedido criado. */
    public void registrar(UUID lojaId, String ip, String telefone) {
        Instant agora = relogio.instant();
        anotar("loja:" + lojaId, agora);
        String tel = telefoneNormalizado(telefone);
        if (tel != null) anotar(chaveTelefone(lojaId, tel), agora);
        if (ip != null) anotar(chaveIp(lojaId, ip), agora);
    }

    /** Tira o que já saiu da janela, para o mapa não crescer para sempre. */
    @Scheduled(fixedRate = 300_000)
    public void limpar() {
        Instant agora = relogio.instant();
        Duration maior = maiorPeriodo();
        registros.entrySet().removeIf(e -> {
            Deque<Instant> fila = e.getValue();
            synchronized (fila) {
                descartarAntigos(fila, agora.minus(maior));
                return fila.isEmpty();
            }
        });
    }

    // ---------------------------------------------------------------- janela deslizante

    private boolean excedeu(String chave, Janela janela, Instant agora) {
        Deque<Instant> fila = registros.get(chave);
        if (fila == null) return false;
        synchronized (fila) {
            descartarAntigos(fila, agora.minus(maiorPeriodo()));
            return fila.stream().filter(t -> t.isAfter(agora.minus(janela.periodo()))).count() >= janela.maximo();
        }
    }

    /** Segundos até o mais antigo da janela sair dela (mínimo 1). */
    private long espera(String chave, Janela janela, Instant agora) {
        Deque<Instant> fila = registros.get(chave);
        if (fila == null) return 1;   // limpar() passou no meio
        synchronized (fila) {
            Instant maisAntigo = fila.stream().filter(t -> t.isAfter(agora.minus(janela.periodo()))).findFirst().orElse(agora);
            return Math.max(1, Duration.between(agora, maisAntigo.plus(janela.periodo())).toSeconds());
        }
    }

    private void anotar(String chave, Instant agora) {
        Deque<Instant> fila = registros.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(agora);
        }
    }

    private static void descartarAntigos(Deque<Instant> fila, Instant limite) {
        while (!fila.isEmpty() && !fila.peekFirst().isAfter(limite)) fila.pollFirst();
    }

    private Duration maiorPeriodo() {
        Duration d = limites.ip().periodo();
        if (limites.telefone().periodo().compareTo(d) > 0) d = limites.telefone().periodo();
        if (limites.loja().periodo().compareTo(d) > 0) d = limites.loja().periodo();
        return d;
    }

    private static String telefoneNormalizado(String telefone) {
        return Cliente.normalizarTelefone(telefone).orElse(null);   // inválido: a validação do pedido recusa depois
    }

    private static String chaveTelefone(UUID lojaId, String tel) {
        return "tel:" + lojaId + ":" + tel;
    }

    private static String chaveIp(UUID lojaId, String ip) {
        return "ip:" + lojaId + ":" + ip;
    }

    /** 429 em ProblemDetail, com Retry-After em segundos. */
    static final class MuitosPedidos extends ResponseStatusException {
        private final long segundos;

        MuitosPedidos(String mensagem, long segundos) {
            super(HttpStatus.TOO_MANY_REQUESTS, mensagem);
            this.segundos = segundos;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
            return headers;
        }
    }
}
