package br.com.menuwin.pedido;

import br.com.menuwin.pagamento.MetodoPagamento;
import br.com.menuwin.pagamento.StatusPagamento;
import br.com.menuwin.shared.Endereco;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pedido feito pelo cliente no cardápio público. Preços NUNCA vêm do cliente: o servidor calcula. */
public final class PedidoPublicoDtos {

    private PedidoPublicoDtos() {}

    /**
     * totalEsperado (opcional): o total que o cliente viu na tela. Se o cardápio mudou nesse meio-tempo,
     * o pedido é recusado (409) em vez de cobrar um valor diferente sem avisar.
     */
    public record CriarPedidoRequest(
            @NotNull TipoEntrega tipoEntrega,
            @NotNull @Valid ClienteRequest cliente,
            @Valid EnderecoRequest endereco,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid ItemRequest> itens,
            @NotNull MetodoPagamento metodoPagamento,
            @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal trocoPara,
            @Size(max = 500) String observacao,
            BigDecimal totalEsperado) {}

    public record ClienteRequest(
            @NotBlank @Size(max = 120) String nome,
            @NotBlank @Size(max = 30) String telefone,
            @Email @Size(max = 160) String email) {}

    public record EnderecoRequest(
            @NotBlank @Size(max = 120) String logradouro,
            @NotBlank @Size(max = 20) String numero,
            @Size(max = 80) String complemento,
            @NotBlank @Size(max = 80) String bairro,
            @NotBlank @Size(max = 80) String cidade,
            @NotBlank @Size(min = 2, max = 2) String uf,
            @Size(max = 9) String cep,
            Double latitude,
            Double longitude) {

        Endereco paraEndereco() {
            return new Endereco(logradouro.trim(), numero.trim(), complemento, bairro.trim(), cidade.trim(),
                    uf.toUpperCase(), cep, latitude, longitude);
        }
    }

    /** quantidade: padrão 1. adicionais: quantidade por unidade do item. */
    public record ItemRequest(
            @NotNull UUID produtoId,
            @Min(1) @Max(99) Integer quantidade,
            @Size(max = 140) String observacao,
            List<@NotNull @Valid AdicionalRequest> adicionais) {

        int quantidadeOuUm() {
            return quantidade == null ? 1 : quantidade;
        }

        List<AdicionalRequest> adicionaisOuVazio() {
            return adicionais == null ? List.of() : adicionais;
        }
    }

    public record AdicionalRequest(@NotNull UUID opcaoId, @Min(1) @Max(20) Integer quantidade) {
        int quantidadeOuUm() {
            return quantidade == null ? 1 : quantidade;
        }
    }

    // ---- resposta ----

    /** pix: só quando metodoPagamento = PIX. O frontend mostra o QR e faz polling em GET /pedidos/{id}. */
    public record PedidoCriadoResponse(UUID id, int numero, StatusPedido status, TipoEntrega tipoEntrega,
                                       MetodoPagamento metodoPagamento, BigDecimal subtotal, BigDecimal taxaEntrega,
                                       BigDecimal desconto, BigDecimal total, List<ItemResumo> itens, PixDto pix) {

        PedidoCriadoResponse comPix(PixDto pix) {
            return new PedidoCriadoResponse(id, numero, status, tipoEntrega, metodoPagamento, subtotal, taxaEntrega,
                    desconto, total, itens, pix);
        }
    }

    public record PixDto(String copiaECola, String qrCodeBase64, Instant expiraEm) {}

    /** pixCopiaECola e pixExpiraEm só enquanto o Pix está pendente. */
    public record PedidoStatusResponse(UUID id, int numero, StatusPedido status, BigDecimal total,
                                       MetodoPagamento metodoPagamento, StatusPagamento statusPagamento,
                                       String pixCopiaECola, Instant pixExpiraEm, String motivoCancelamento) {}

    public record ItemResumo(String nome, int quantidade, BigDecimal precoUnitario, BigDecimal totalItem,
                             String observacao, List<AdicionalResumo> adicionais) {}

    public record AdicionalResumo(String grupo, String opcao, int quantidade, BigDecimal preco) {}
}
