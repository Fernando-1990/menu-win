package br.com.menuwin.shared;

/** Gravado como JSONB (lojas.endereco e pedidos.endereco_entrega). */
public record Endereco(
        String logradouro,
        String numero,
        String complemento,
        String bairro,
        String cidade,
        String uf,
        String cep,
        Double latitude,
        Double longitude) {
}
