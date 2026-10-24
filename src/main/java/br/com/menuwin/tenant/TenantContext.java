package br.com.menuwin.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Guarda a loja da requisição atual.
 * Quem preenche: TenantFilter (cardápio público) e PainelTenantFilter (painel, a partir do JWT).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> LOJA_ATUAL = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID lojaId) {
        LOJA_ATUAL.set(lojaId);
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(LOJA_ATUAL.get());
    }

    public static UUID getObrigatorio() {
        return get().orElseThrow(() -> new IllegalStateException("Nenhuma loja definida para esta requisição"));
    }

    /** Sempre chamar no finally: threads do Tomcat são reaproveitadas. */
    public static void clear() {
        LOJA_ATUAL.remove();
    }
}
