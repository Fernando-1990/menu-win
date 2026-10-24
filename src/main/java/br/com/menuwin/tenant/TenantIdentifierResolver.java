package br.com.menuwin.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Diz ao Hibernate qual é a loja atual. Com isso, toda entidade que tem um campo
 * anotado com @TenantId ganha automaticamente:
 *  - "WHERE loja_id = ?" em toda consulta JPQL/Criteria/derived query;
 *  - loja_id preenchido sozinho no INSERT.
 *
 * Sem loja definida, devolve um UUID "zerado": as consultas voltam vazias e
 * os inserts falham na FK. Ou seja, o erro é seguro (fail-safe).
 *
 * ATENÇÃO: queries nativas (nativeQuery = true) NÃO recebem o filtro automático.
 */
@Component
public class TenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    static final UUID NENHUMA_LOJA = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.get().orElse(NENHUMA_LOJA);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
