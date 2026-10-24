package br.com.menuwin.loja;

import br.com.menuwin.tenant.TenantContext;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/** Como a loja atende pelo cardápio. A loja é o tenant: sempre a do token (TenantContext), nunca um id vindo do request. */
@Service
@RequiredArgsConstructor
public class OperacaoLojaService {

    private final LojaRepository lojaRepository;

    public record Operacao(
            boolean aceitaRetirada,
            boolean aceitaDelivery,
            boolean aceitaConsumoLocal,
            boolean aceitaPagarNoCaixa,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal taxaEntregaPadrao,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal pedidoMinimo,
            @Min(0) @Max(600) int tempoPreparoMin) {

        static Operacao de(Loja l) {
            return new Operacao(l.isAceitaRetirada(), l.isAceitaDelivery(), l.isAceitaConsumoLocal(),
                    l.isAceitaPagarNoCaixa(), l.getTaxaEntregaPadrao(), l.getPedidoMinimo(), l.getTempoPreparoMin());
        }
    }

    @Transactional(readOnly = true)
    public Operacao consultar() {
        return Operacao.de(lojaAtual());
    }

    @Transactional
    public Operacao atualizar(Operacao op) {
        if (!op.aceitaRetirada() && !op.aceitaDelivery() && !op.aceitaConsumoLocal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Habilite pelo menos uma forma de atendimento: retirada, delivery ou consumo no local");
        }
        Loja loja = lojaAtual();
        loja.setAceitaRetirada(op.aceitaRetirada());
        loja.setAceitaDelivery(op.aceitaDelivery());
        loja.setAceitaConsumoLocal(op.aceitaConsumoLocal());
        loja.setAceitaPagarNoCaixa(op.aceitaPagarNoCaixa());
        loja.setTaxaEntregaPadrao(op.taxaEntregaPadrao());
        loja.setPedidoMinimo(op.pedidoMinimo());
        loja.setTempoPreparoMin(op.tempoPreparoMin());
        return Operacao.de(loja);
    }

    private Loja lojaAtual() {
        return lojaRepository.findById(TenantContext.getObrigatorio()).orElseThrow();
    }
}
