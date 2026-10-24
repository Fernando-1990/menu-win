package br.com.menuwin.pagamento;

import br.com.menuwin.pagamento.GatewayPagamento.GatewayException;
import br.com.menuwin.shared.Cifrador;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/** Token do Mercado Pago da loja atual. Entra pelo painel (DONO), sai só decifrado para o gateway. */
@Service
@RequiredArgsConstructor
public class CredenciaisService {

    private final CredencialGatewayRepository repository;
    private final GatewayPagamento gateway;
    private final Cifrador cifrador;
    private final TransactionTemplate tx;

    public record StatusCredencial(boolean configurado, boolean modoTeste) {}

    /** Valida o token no próprio Mercado Pago antes de salvar (chamada HTTP fora de transação). */
    public StatusCredencial salvarMercadoPago(String accessToken) {
        String token = accessToken.trim();
        try {
            gateway.validarCredencial(token);
        } catch (GatewayException e) {
            if (e.isCredencialInvalida()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Access token recusado pelo Mercado Pago");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mercado Pago indisponível. Tente de novo.");
        }
        return tx.execute(s -> {
            CredencialGateway c = repository.findByGateway(CredencialGateway.MERCADO_PAGO).orElseGet(CredencialGateway::new);
            c.setGateway(CredencialGateway.MERCADO_PAGO);
            c.setAccessTokenCifrado(cifrador.cifrar(token));
            c.setModoTeste(token.startsWith("TEST-"));
            repository.save(c);
            return new StatusCredencial(true, c.isModoTeste());
        });
    }

    @Transactional(readOnly = true)
    public StatusCredencial statusMercadoPago() {
        return repository.findByGateway(CredencialGateway.MERCADO_PAGO)
                .map(c -> new StatusCredencial(true, c.isModoTeste()))
                .orElse(new StatusCredencial(false, false));
    }

    @Transactional
    public void removerMercadoPago() {
        repository.findByGateway(CredencialGateway.MERCADO_PAGO).ifPresent(repository::delete);
    }

    @Transactional(readOnly = true)
    public boolean aceitaPix() {
        return repository.existsByGateway(CredencialGateway.MERCADO_PAGO);
    }

    @Transactional(readOnly = true)
    public Optional<String> tokenMercadoPago() {
        return repository.findByGateway(CredencialGateway.MERCADO_PAGO)
                .map(c -> cifrador.decifrar(c.getAccessTokenCifrado()));
    }
}
