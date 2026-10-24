-- Dados de exemplo (só no profile dev). Repeatable: o Flyway roda de novo se o arquivo mudar.
INSERT INTO lojas (id, slug, nome, cor_primaria, layout, aceita_delivery, taxa_entrega_padrao)
VALUES ('00000000-0000-0000-0000-00000000a001', 'padaria-demo', 'Padaria Demo', '#B5651D', 'GRADE', TRUE, 5.00)
ON CONFLICT (id) DO NOTHING;

-- Login do painel (só dev): dono@padaria-demo.com / padaria123
INSERT INTO usuarios (id, loja_id, nome, email, senha_hash, papel) VALUES
 ('00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-00000000a001', 'Dono da Padaria',
  'dono@padaria-demo.com', '$2a$10$/bIH5YWCBKxKHiYISkpr1eTb8bLAvF53Hxx93kw9oxwBA9kqa4hFC', 'DONO')
ON CONFLICT (id) DO NOTHING;

INSERT INTO categorias (id, loja_id, nome, ordem) VALUES
 ('00000000-0000-0000-0000-00000000c001', '00000000-0000-0000-0000-00000000a001', 'Pães',    1),
 ('00000000-0000-0000-0000-00000000c002', '00000000-0000-0000-0000-00000000a001', 'Lanches', 2),
 ('00000000-0000-0000-0000-00000000c003', '00000000-0000-0000-0000-00000000a001', 'Bebidas', 3)
ON CONFLICT (id) DO NOTHING;

INSERT INTO produtos (id, loja_id, categoria_id, nome, descricao, preco, preco_promocional, ordem) VALUES
 ('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000a001', '00000000-0000-0000-0000-00000000c001', 'Pão francês (un.)', 'Saído do forno', 0.90, NULL, 1),
 ('00000000-0000-0000-0000-00000000d002', '00000000-0000-0000-0000-00000000a001', '00000000-0000-0000-0000-00000000c001', 'Pão de queijo (un.)', NULL, 4.50, NULL, 2),
 ('00000000-0000-0000-0000-00000000d003', '00000000-0000-0000-0000-00000000a001', '00000000-0000-0000-0000-00000000c002', 'Misto quente', 'Pão de forma, presunto e queijo', 12.00, 10.00, 1),
 ('00000000-0000-0000-0000-00000000d004', '00000000-0000-0000-0000-00000000a001', '00000000-0000-0000-0000-00000000c003', 'Café com leite', NULL, 6.00, NULL, 1)
ON CONFLICT (id) DO NOTHING;
