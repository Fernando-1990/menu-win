package br.com.menuwin.pedido;

import br.com.menuwin.pedido.NotificadorCozinha.PedidoAtualizado;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conexões SSE abertas, separadas por loja: evento da loja A nunca vai para a tela da loja B.
 *
 * EM MEMÓRIA: funciona com UMA instância da API. Com várias, trocar por Postgres LISTEN/NOTIFY ou Redis.
 *
 * Eventos:
 *  - "conectado": logo ao abrir. O frontend deve (re)carregar GET /api/painel/pedidos/abertos.
 *  - "pedido": PedidoCozinhaDto completo; substituir na lista pelo id.
 *  - comentário a cada 25s: mantém a conexão viva em proxies.
 * Sem replay: ao reconectar, recarregar a lista (pode ter perdido eventos no meio).
 */
@Slf4j
@Component
public class CentralEventosCozinha {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();   // o cliente reconecta sozinho

    private final Map<UUID, Set<SseEmitter>> porLoja = new ConcurrentHashMap<>();

    public SseEmitter conectar(UUID lojaId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> daLoja = porLoja.computeIfAbsent(lojaId, k -> ConcurrentHashMap.newKeySet());
        daLoja.add(emitter);
        Runnable remover = () -> daLoja.remove(emitter);
        emitter.onCompletion(remover);
        emitter.onTimeout(remover);
        emitter.onError(e -> remover.run());
        enviar(daLoja, emitter, SseEmitter.event().name("conectado").data("ok"));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizar(PedidoAtualizado evento) {
        Set<SseEmitter> daLoja = porLoja.get(evento.lojaId());
        if (daLoja == null) return;
        for (SseEmitter emitter : daLoja) {
            enviar(daLoja, emitter, SseEmitter.event().name("pedido").data(evento.pedido(), MediaType.APPLICATION_JSON));
        }
    }

    @Scheduled(fixedRate = 25_000)
    public void manterVivo() {
        porLoja.values().forEach(daLoja ->
                daLoja.forEach(emitter -> enviar(daLoja, emitter, SseEmitter.event().comment("ping"))));
    }

    /** send não é thread-safe por emitter; conexão caída sai da lista. */
    private static void enviar(Set<SseEmitter> daLoja, SseEmitter emitter, SseEventBuilder evento) {
        try {
            synchronized (emitter) {
                emitter.send(evento);
            }
        } catch (Exception e) {
            log.debug("Conexão SSE caiu; removendo", e);
            daLoja.remove(emitter);
        }
    }
}
