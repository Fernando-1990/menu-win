package br.com.menuwin.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Erros de "senha atual" na troca de senha, por usuário: com o painel aberto ou um token na mão,
 * ninguém fica testando senhas à vontade. Em memória (uma instância da API), zera ao reiniciar.
 */
class TentativasSenha {

    static final int MAXIMO = 5;
    static final Duration JANELA = Duration.ofMinutes(15);

    private final Map<UUID, Deque<Instant>> erros = new ConcurrentHashMap<>();

    void verificar(UUID usuarioId) {
        Deque<Instant> fila = erros.get(usuarioId);
        if (fila == null) return;
        synchronized (fila) {
            Instant limite = Instant.now().minus(JANELA);
            while (!fila.isEmpty() && fila.peekFirst().isBefore(limite)) fila.pollFirst();
            if (fila.size() >= MAXIMO) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Muitas tentativas com a senha atual errada. Aguarde 15 minutos e tente de novo.");
            }
        }
    }

    void registrarErro(UUID usuarioId) {
        Deque<Instant> fila = erros.computeIfAbsent(usuarioId, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(Instant.now());
        }
    }

    void limpar(UUID usuarioId) {
        erros.remove(usuarioId);
    }
}
