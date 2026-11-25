-- Botão "pausar pedidos" do painel: cozinha lotada, acabou o gás... O cardápio continua visível, mas não aceita pedido.
ALTER TABLE lojas ADD COLUMN pedidos_pausados BOOLEAN NOT NULL DEFAULT FALSE;
