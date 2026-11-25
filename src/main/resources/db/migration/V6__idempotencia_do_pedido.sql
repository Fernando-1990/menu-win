-- Chave de idempotência do pedido público (header Idempotency-Key, gerada pelo navegador por tentativa).
-- Mesma chave na mesma loja = mesmo pedido: retry de rede ou clique duplo não cria pedido repetido.
ALTER TABLE pedidos ADD COLUMN chave_idempotencia UUID;

CREATE UNIQUE INDEX uq_pedidos_loja_chave ON pedidos (loja_id, chave_idempotencia)
    WHERE chave_idempotencia IS NOT NULL;
