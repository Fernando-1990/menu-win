-- Cardápio de demonstração da loja "padaria-demo" (produção), para mostrar o app a possíveis clientes.
-- Onde rodar: Supabase > SQL Editor. Pode rodar de novo: não duplica nada e não apaga o que já existe.
--  - Categoria ou produto com o mesmo nome já cadastrado é reaproveitado (só ganha foto/descrição se não tiver).
--  - Liga "pagar no caixa", "comer no local" e "retirada" na loja.
--
-- Fotos: ficam no front (menu-win-front/public/demo), vindas do Wikimedia Commons com licenças livres
-- (créditos no fim e em public/demo/CREDITOS.txt). Servem para DEMONSTRAÇÃO.
-- Para uma loja de verdade, usar fotos da própria loja.

DO $$
DECLARE
    v_loja      UUID;
    c_paes      UUID;
    c_lanches   UUID;
    c_doces     UUID;
    c_bebidas   UUID;
    g_recheio   UUID;
    g_pao       UUID;
    g_catupiry  UUID;
    g_adocar    UUID;
    g_tamanho   UUID;
    w CONSTANT TEXT := 'https://menu-win.netlify.app/demo/';   -- fotos servidas pelo front (public/demo)
BEGIN
    SELECT id INTO v_loja FROM menuwin.lojas WHERE slug = 'padaria-demo';
    IF v_loja IS NULL THEN
        RAISE EXCEPTION 'Loja padaria-demo não encontrada';
    END IF;

    UPDATE menuwin.lojas
       SET aceita_pagar_no_caixa = TRUE,
           aceita_consumo_local  = TRUE,
           aceita_retirada       = TRUE,
           tempo_preparo_min     = 15,
           capa_url = COALESCE(capa_url, w || 'pao-frances.jpg')
     WHERE id = v_loja;

    -- ---------------------------------------------------------------- categorias
    CREATE TEMP TABLE IF NOT EXISTS _cat (nome TEXT, ordem INT) ON COMMIT DROP;
    INSERT INTO _cat VALUES ('Pães', 1), ('Lanches e salgados', 2), ('Doces', 3), ('Bebidas', 4);
    INSERT INTO menuwin.categorias (loja_id, nome, ordem)
    SELECT v_loja, c.nome, c.ordem FROM _cat c
     WHERE NOT EXISTS (SELECT 1 FROM menuwin.categorias x WHERE x.loja_id = v_loja AND x.nome = c.nome);
    -- ordem do cardápio: estas quatro primeiro; outras categorias que o dono criou vêm depois
    UPDATE menuwin.categorias x SET ordem = c.ordem FROM _cat c WHERE x.loja_id = v_loja AND x.nome = c.nome;
    UPDATE menuwin.categorias x SET ordem = 10
     WHERE x.loja_id = v_loja AND x.nome NOT IN (SELECT nome FROM _cat) AND x.ordem < 10;

    SELECT id INTO c_paes    FROM menuwin.categorias WHERE loja_id = v_loja AND nome = 'Pães'               LIMIT 1;
    SELECT id INTO c_lanches FROM menuwin.categorias WHERE loja_id = v_loja AND nome = 'Lanches e salgados' LIMIT 1;
    SELECT id INTO c_doces   FROM menuwin.categorias WHERE loja_id = v_loja AND nome = 'Doces'              LIMIT 1;
    SELECT id INTO c_bebidas FROM menuwin.categorias WHERE loja_id = v_loja AND nome = 'Bebidas'            LIMIT 1;

    -- ---------------------------------------------------------------- produtos
    CREATE TEMP TABLE IF NOT EXISTS _prod (
        categoria UUID, nome TEXT, descricao TEXT, preco NUMERIC, promo NUMERIC, ordem INT, foto TEXT
    ) ON COMMIT DROP;
    INSERT INTO _prod VALUES
     (c_paes, 'Pão francês', 'Quentinho, saindo do forno a cada hora. Preço por unidade.', 0.90, NULL, 1,
      w || 'pao-frances.jpg'),
     (c_paes, 'Pão de queijo', 'Porção com 5 unidades, feito com queijo canastra.', 9.90, NULL, 2,
      w || 'pao-de-queijo.jpg'),
     (c_paes, 'Croissant', 'Massa folhada amanteigada, crocante por fora e macia por dentro.', 8.50, NULL, 3,
      w || 'croissant.jpg'),
     (c_paes, 'Pão italiano fatiado', 'Fermentação natural, casca crocante. Unidade de 500 g.', 16.00, NULL, 4,
      w || 'pao-italiano.jpg'),

     (c_lanches, 'Pão na chapa', 'Pão francês na chapa com manteiga.', 6.50, NULL, 1,
      w || 'pao-na-chapa.jpg'),
     (c_lanches, 'Misto quente', 'Presunto e queijo derretido no pão tostado.', 14.00, 12.00, 2,
      w || 'misto-quente.jpg'),
     (c_lanches, 'Coxinha de frango', 'Massa leve e recheio cremoso de frango desfiado.', 8.00, NULL, 3,
      w || 'coxinha.jpg'),
     (c_lanches, 'Esfiha de carne', 'Aberta, com carne temperada, tomate e pinoli.', 7.00, NULL, 4,
      w || 'esfiha.jpg'),
     (c_lanches, 'Quiche de alho-poró', 'Fatia generosa, massa amanteigada.', 16.00, NULL, 5,
      w || 'quiche.jpg'),

     (c_doces, 'Bolo de cenoura com chocolate', 'Fatia com cobertura de chocolate e granulado.', 9.00, NULL, 1,
      w || 'bolo-de-cenoura.jpg'),
     (c_doces, 'Sonho recheado', 'Polvilhado com açúcar e canela, recheio de goiabada.', 8.00, NULL, 2,
      w || 'sonho.jpg'),
     (c_doces, 'Brigadeiro', 'Tradicional, com granulado de chocolate. Unidade.', 4.00, 3.50, 3,
      w || 'brigadeiro.jpg'),

     (c_bebidas, 'Café expresso', 'Grãos selecionados, moídos na hora.', 6.00, NULL, 1,
      w || 'expresso.jpg'),
     (c_bebidas, 'Cappuccino', 'Expresso, leite vaporizado e canela.', 12.00, NULL, 2,
      w || 'cappuccino.jpg'),
     (c_bebidas, 'Suco de laranja', 'Natural, espremido na hora. Copo de 300 ml.', 10.00, NULL, 3,
      w || 'suco-de-laranja.jpg');

    -- já existe com o mesmo nome: mantém preço e categoria do dono, só completa foto e descrição
    UPDATE menuwin.produtos p
       SET foto_url = COALESCE(p.foto_url, n.foto),
           descricao = COALESCE(p.descricao, n.descricao),
           atualizado_em = now()
      FROM _prod n
     WHERE p.loja_id = v_loja AND p.ativo AND p.nome = n.nome;

    INSERT INTO menuwin.produtos (loja_id, categoria_id, nome, descricao, foto_url, preco, preco_promocional, ordem)
    SELECT v_loja, n.categoria, n.nome, n.descricao, n.foto, n.preco, n.promo, n.ordem FROM _prod n
     WHERE NOT EXISTS (SELECT 1 FROM menuwin.produtos x WHERE x.loja_id = v_loja AND x.ativo AND x.nome = n.nome);

    -- ---------------------------------------------------------------- adicionais
    -- cria o grupo (com as opções) só se a loja ainda não tiver um com o mesmo nome
    INSERT INTO menuwin.grupos_adicionais (loja_id, nome, minimo, maximo)
    SELECT v_loja, g.nome, g.minimo, g.maximo
      FROM (VALUES ('Turbine seu lanche', 0, 3), ('Escolha o pão', 1, 1), ('Extra', 0, 1),
                   ('Como prefere adoçar?', 0, 1), ('Tamanho', 1, 1)) AS g(nome, minimo, maximo)
     WHERE NOT EXISTS (SELECT 1 FROM menuwin.grupos_adicionais x WHERE x.loja_id = v_loja AND x.nome = g.nome);

    SELECT id INTO g_recheio  FROM menuwin.grupos_adicionais WHERE loja_id = v_loja AND nome = 'Turbine seu lanche'   LIMIT 1;
    SELECT id INTO g_pao      FROM menuwin.grupos_adicionais WHERE loja_id = v_loja AND nome = 'Escolha o pão'        LIMIT 1;
    SELECT id INTO g_catupiry FROM menuwin.grupos_adicionais WHERE loja_id = v_loja AND nome = 'Extra'                LIMIT 1;
    SELECT id INTO g_adocar   FROM menuwin.grupos_adicionais WHERE loja_id = v_loja AND nome = 'Como prefere adoçar?' LIMIT 1;
    SELECT id INTO g_tamanho  FROM menuwin.grupos_adicionais WHERE loja_id = v_loja AND nome = 'Tamanho'              LIMIT 1;

    INSERT INTO menuwin.opcoes_adicionais (grupo_id, nome, preco, ordem)
    SELECT o.grupo, o.nome, o.preco, o.ordem
      FROM (VALUES (g_recheio, 'Requeijão', 2.00, 1), (g_recheio, 'Queijo prato', 3.00, 2), (g_recheio, 'Ovo', 2.50, 3),
                   (g_recheio, 'Presunto', 3.00, 4),
                   (g_pao, 'Pão de forma', 0.00, 1), (g_pao, 'Pão francês', 0.00, 2), (g_pao, 'Pão de leite', 1.00, 3),
                   (g_catupiry, 'Com catupiry', 2.00, 1),
                   (g_adocar, 'Açúcar', 0.00, 1), (g_adocar, 'Adoçante', 0.00, 2),
                   (g_tamanho, 'Pequeno', 0.00, 1), (g_tamanho, 'Grande', 4.00, 2)) AS o(grupo, nome, preco, ordem)
     WHERE NOT EXISTS (SELECT 1 FROM menuwin.opcoes_adicionais x WHERE x.grupo_id = o.grupo);

    -- liga grupos aos produtos. ATENÇÃO: "ordem" aqui é o índice de uma lista (@OrderColumn) e começa
    -- em 0, sem buracos; um buraco quebra o cardápio inteiro (500). Por isso só liga produto sem grupo.
    INSERT INTO menuwin.produto_grupos_adicionais (produto_id, grupo_id, ordem)
    SELECT p.id, l.grupo, l.ordem
      FROM (VALUES ('Pão na chapa', g_recheio, 0),
                   ('Misto quente', g_pao, 0), ('Misto quente', g_recheio, 1),
                   ('Coxinha de frango', g_catupiry, 0),
                   ('Café expresso', g_adocar, 0),
                   ('Cappuccino', g_tamanho, 0), ('Cappuccino', g_adocar, 1),
                   ('Suco de laranja', g_adocar, 0)) AS l(produto, grupo, ordem)
      JOIN menuwin.produtos p ON p.loja_id = v_loja AND p.ativo AND p.nome = l.produto
     WHERE NOT EXISTS (SELECT 1 FROM menuwin.produto_grupos_adicionais x WHERE x.produto_id = p.id);
END $$;

-- Créditos das fotos (Wikimedia Commons):
--  Pão francês / capa: "Bread in baskets, Salvador, Brazil, 2011", Rosino, CC BY-SA 2.0
--  Pão de queijo: Jonathan Wilkins, CC BY-SA 3.0        Croissant: Stephanie (Flickr), CC BY 2.0
--  Pão italiano: Daniel Sone / NCI, domínio público      Pão na chapa: Melsj, CC0
--  Misto quente: Tamorlan, CC BY-SA 3.0                  Coxinha: Sintegrity, CC BY-SA 4.0
--  Esfiha: Basel15, domínio público                      Quiche: timothy muza (Unsplash), CC0
--  Bolo de cenoura: Sintegrity, CC BY-SA 4.0             Sonho: Sebastian Koppehel, CC BY 4.0
--  Brigadeiro: Márcia Cristina Machado, CC BY-SA 4.0     Expresso: Deerpeter, CC BY-SA 4.0
--  Cappuccino: Peachyeung316, CC BY-SA 4.0               Suco de laranja: Carmenborass, CC BY-SA 4.0
