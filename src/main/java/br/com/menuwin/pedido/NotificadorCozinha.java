package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.PagamentoRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Avisa as telas da loja que um pedido mudou. Chamar DENTRO da transação que fez a mudança:
 * o DTO é montado aqui (com o estado gravado), mas o envio só acontece depois do commit
 * (CentralEventosCozinha). Rollback = nada é enviado.
 */
@Component
@RequiredArgsConstructor
public class NotificadorCozinha {

    public record PedidoAtualizado(UUID lojaId, PedidoCozinhaDto pedido) {}

    private final ApplicationEventPublisher eventos;
    private final PagamentoRepository pagamentoRepository;
    private final EntityManager em;

    public PedidoCozinhaDto pedidoMudou(Pedido pedido) {
        em.flush();   // ids, criadoEm e histórico gravados antes de montar o DTO
        PedidoCozinhaDto dto = PedidoCozinhaDto.de(pedido,
                pagamentoRepository.findFirstByPedidoIdOrderByCriadoEmDesc(pedido.getId()).orElse(null));
        eventos.publishEvent(new PedidoAtualizado(pedido.getLojaId(), dto));
        return dto;
    }
}
