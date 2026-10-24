-- Configurações escolhidas pelo dono da loja. Começam desligadas: nada muda para quem já usa.
ALTER TABLE lojas
    ADD COLUMN aceita_consumo_local  BOOLEAN NOT NULL DEFAULT FALSE,   -- "comer aqui" pelo cardápio
    ADD COLUMN aceita_pagar_no_caixa BOOLEAN NOT NULL DEFAULT FALSE;   -- pedido vai para a cozinha e paga ao sair/retirar

-- NO_CAIXA: o cliente escolhe dinheiro, cartão ou Pix na hora de pagar, no balcão.
ALTER TABLE pagamentos
    DROP CONSTRAINT pagamentos_metodo_check,
    ADD CONSTRAINT pagamentos_metodo_check
        CHECK (metodo IN ('PIX', 'CARTAO_ONLINE', 'DINHEIRO_NA_ENTREGA', 'CARTAO_NA_ENTREGA', 'NO_CAIXA'));
