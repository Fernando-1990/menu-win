-- Credencial de cada loja no gateway de pagamento: o dinheiro do pedido cai direto na conta da loja.
-- O token é gravado cifrado (AES-256-GCM, chave em CRYPTO_KEY), nunca em texto puro.
CREATE TABLE credenciais_gateway (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id               UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    gateway               VARCHAR(30) NOT NULL CHECK (gateway IN ('MERCADO_PAGO')),
    access_token_cifrado  TEXT NOT NULL,
    modo_teste            BOOLEAN NOT NULL DEFAULT FALSE,
    atualizado_em         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (loja_id, gateway)
);
