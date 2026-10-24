-- O pedido guarda nome e telefone do cliente como estavam na hora do pedido (igual a produtos e preços).
-- Antes vinham do cadastro do cliente, que é atualizado a cada pedido do mesmo telefone: o histórico mudava.
ALTER TABLE pedidos
    ADD COLUMN nome_cliente     VARCHAR(120),
    ADD COLUMN telefone_cliente VARCHAR(20);

-- Pedidos antigos: o melhor dado disponível é o cadastro atual.
UPDATE pedidos p SET nome_cliente = c.nome, telefone_cliente = c.telefone
FROM clientes c WHERE c.id = p.cliente_id;

-- Histórico do painel: pedidos da loja por período, mais recentes primeiro.
CREATE INDEX idx_pedidos_loja_criado ON pedidos (loja_id, criado_em DESC);
