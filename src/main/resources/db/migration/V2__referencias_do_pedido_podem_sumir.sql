-- Itens de pedido guardam snapshot (nome e preço). A referência ao cardápio é só informativa:
-- se o lojista apagar uma opção (ou um grupo inteiro), o pedido antigo fica com opcao_id nulo
-- em vez de bloquear a exclusão.
ALTER TABLE itens_pedido_adicionais
    DROP CONSTRAINT itens_pedido_adicionais_opcao_id_fkey,
    ADD CONSTRAINT itens_pedido_adicionais_opcao_id_fkey
        FOREIGN KEY (opcao_id) REFERENCES opcoes_adicionais(id) ON DELETE SET NULL;

-- Produtos usam soft delete, mas o comentário do V1 já dizia "referência; pode sumir".
ALTER TABLE itens_pedido
    DROP CONSTRAINT itens_pedido_produto_id_fkey,
    ADD CONSTRAINT itens_pedido_produto_id_fkey
        FOREIGN KEY (produto_id) REFERENCES produtos(id) ON DELETE SET NULL;
