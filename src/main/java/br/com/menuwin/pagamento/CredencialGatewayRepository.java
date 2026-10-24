package br.com.menuwin.pagamento;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Filtrado pela loja atual (@TenantId). */
public interface CredencialGatewayRepository extends JpaRepository<CredencialGateway, UUID> {

    Optional<CredencialGateway> findByGateway(String gateway);

    boolean existsByGateway(String gateway);
}
