-- Cadastra uma loja e o login do dono em produção (até existir a tela de cadastro).
-- Onde rodar: Supabase > SQL Editor, DEPOIS do primeiro deploy da API (é ela que cria as tabelas).
-- Troque os 5 valores marcados com <<< antes de rodar. A senha vira hash BCrypt aqui mesmo (pgcrypto);
-- o texto da senha não fica gravado, mas fica no histórico do SQL Editor: apague a consulta depois.

WITH nova_loja AS (
    INSERT INTO menuwin.lojas (slug, nome, cor_primaria, layout)
    VALUES ('padaria-do-ze',                    -- <<< endereço do cardápio: só minúsculas, números e hífen
            'Padaria do Zé',                    -- <<< nome que o cliente vê
            '#B5651D',                          -- cor principal (pode trocar depois no painel)
            'GRADE')
    RETURNING id
)
INSERT INTO menuwin.usuarios (loja_id, nome, email, senha_hash, papel)
SELECT id,
       'José da Silva',                         -- <<< nome do dono
       'dono@padariadoze.com.br',               -- <<< e-mail de login
       extensions.crypt('troque-esta-senha', extensions.gen_salt('bf', 10)),   -- <<< senha (use uma senha forte)
       'DONO'
FROM nova_loja;
