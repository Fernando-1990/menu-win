package br.com.menuwin.pagamento;

/** NO_CAIXA: pedido vai direto para a cozinha; o cliente paga no balcão (dinheiro, cartão ou Pix) ao sair ou retirar. */
public enum MetodoPagamento {
    PIX, CARTAO_ONLINE, DINHEIRO_NA_ENTREGA, CARTAO_NA_ENTREGA, NO_CAIXA;

    /** Pago em mãos (balcão ou entregador): alguém da loja marca como recebido. Os online o gateway confirma. */
    public boolean recebidoPelaLoja() {
        return this == DINHEIRO_NA_ENTREGA || this == CARTAO_NA_ENTREGA || this == NO_CAIXA;
    }
}
