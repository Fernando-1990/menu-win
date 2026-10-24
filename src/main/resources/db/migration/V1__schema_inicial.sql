-- =====================================================================
-- Menu Win — Schema inicial do MVP (PostgreSQL 15+)
-- Migration Flyway: src/main/resources/db/migration/V1__schema_inicial.sql
--
-- Princípios:
--  * Multi-tenant por coluna: toda tabela de negócio tem loja_id
--  * UUID como PK (não expõe volume de pedidos/lojas nas URLs)
--  * Dinheiro em NUMERIC(10,2) -> BigDecimal no Java
--  * Pedido guarda "foto" de nome/preço: alterar o cardápio não altera histórico
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()

-- ---------------------------------------------------------------------
-- 1. LOJA (o tenant) + identidade visual
-- ---------------------------------------------------------------------
CREATE TABLE lojas (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                    VARCHAR(60)  NOT NULL UNIQUE,     -- menuwin.com.br/padaria-do-ze
    nome                    VARCHAR(120) NOT NULL,
    documento               VARCHAR(14),                      -- CNPJ ou CPF, só dígitos
    telefone                VARCHAR(20),
    endereco                JSONB,                            -- {logradouro, numero, bairro, cidade, uf, cep, lat, lng}
    -- identidade visual
    logo_url                TEXT,
    capa_url                TEXT,
    cor_primaria            VARCHAR(7)   NOT NULL DEFAULT '#C0392B',
    layout                  VARCHAR(20)  NOT NULL DEFAULT 'LISTA'
                            CHECK (layout IN ('LISTA', 'GRADE', 'DESTAQUE', 'ESCURO')),
    mostrar_so_com_foto     BOOLEAN      NOT NULL DEFAULT FALSE,
    -- operação
    aceita_retirada         BOOLEAN      NOT NULL DEFAULT TRUE,
    aceita_delivery         BOOLEAN      NOT NULL DEFAULT FALSE,
    taxa_entrega_padrao     NUMERIC(10,2) NOT NULL DEFAULT 0,
    pedido_minimo           NUMERIC(10,2) NOT NULL DEFAULT 0,
    tempo_preparo_min       INT          NOT NULL DEFAULT 30,
    proximo_numero_pedido   INT          NOT NULL DEFAULT 1,  -- número curto exibido (#0128)
    ativa                   BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE horarios_funcionamento (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id     UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    dia_semana  SMALLINT NOT NULL CHECK (dia_semana BETWEEN 1 AND 7), -- 1=segunda (ISO)
    abre        TIME NOT NULL,
    fecha       TIME NOT NULL,
    CHECK (abre <> fecha)
);
CREATE INDEX idx_horarios_loja ON horarios_funcionamento(loja_id);

-- ---------------------------------------------------------------------
-- 2. USUÁRIOS DO PAINEL (lojista e equipe)
-- ---------------------------------------------------------------------
CREATE TABLE usuarios (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id       UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    nome          VARCHAR(120) NOT NULL,
    email         VARCHAR(160) NOT NULL UNIQUE,
    senha_hash    VARCHAR(100) NOT NULL,                       -- BCrypt
    papel         VARCHAR(20)  NOT NULL CHECK (papel IN ('DONO', 'GERENTE', 'COZINHA')),
    ativo         BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_usuarios_loja ON usuarios(loja_id);

-- ---------------------------------------------------------------------
-- 3. CARDÁPIO
-- ---------------------------------------------------------------------
CREATE TABLE categorias (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id     UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    nome        VARCHAR(80) NOT NULL,
    ordem       INT     NOT NULL DEFAULT 0,
    ativa       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_categorias_loja ON categorias(loja_id, ordem);

CREATE TABLE produtos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id         UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    categoria_id    UUID NOT NULL REFERENCES categorias(id),
    nome            VARCHAR(120) NOT NULL,
    descricao       TEXT,
    foto_url        TEXT,
    preco           NUMERIC(10,2) NOT NULL CHECK (preco >= 0),
    preco_promocional NUMERIC(10,2) CHECK (preco_promocional >= 0),
    ordem           INT     NOT NULL DEFAULT 0,
    disponivel      BOOLEAN NOT NULL DEFAULT TRUE,   -- "acabou hoje", sem esconder do painel
    ativo           BOOLEAN NOT NULL DEFAULT TRUE,   -- soft delete
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_produtos_loja_categoria ON produtos(loja_id, categoria_id, ordem);

-- Grupos de adicionais: "Escolha o pão" (min 1, max 1), "Adicionais" (min 0, max 5)
CREATE TABLE grupos_adicionais (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id     UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    nome        VARCHAR(80) NOT NULL,
    minimo      INT NOT NULL DEFAULT 0 CHECK (minimo >= 0),
    maximo      INT NOT NULL DEFAULT 1 CHECK (maximo >= 1),
    CHECK (minimo <= maximo)
);

CREATE TABLE opcoes_adicionais (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grupo_id    UUID NOT NULL REFERENCES grupos_adicionais(id) ON DELETE CASCADE,
    nome        VARCHAR(80) NOT NULL,
    preco       NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (preco >= 0),
    disponivel  BOOLEAN NOT NULL DEFAULT TRUE,
    ordem       INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_opcoes_grupo ON opcoes_adicionais(grupo_id);

-- Um grupo pode ser reutilizado em vários produtos
CREATE TABLE produto_grupos_adicionais (
    produto_id  UUID NOT NULL REFERENCES produtos(id) ON DELETE CASCADE,
    grupo_id    UUID NOT NULL REFERENCES grupos_adicionais(id) ON DELETE CASCADE,
    ordem       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (produto_id, grupo_id)
);

-- ---------------------------------------------------------------------
-- 4. CLIENTES (por loja — cada loja é dona da sua base)
-- ---------------------------------------------------------------------
CREATE TABLE clientes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id     UUID NOT NULL REFERENCES lojas(id) ON DELETE CASCADE,
    nome        VARCHAR(120) NOT NULL,
    telefone    VARCHAR(20)  NOT NULL,                        -- normalizado: 5511999999999
    email       VARCHAR(160),
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (loja_id, telefone)
);

-- ---------------------------------------------------------------------
-- 5. PEDIDOS
-- ---------------------------------------------------------------------
CREATE TABLE pedidos (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loja_id             UUID NOT NULL REFERENCES lojas(id),
    numero              INT  NOT NULL,                        -- número curto por loja
    cliente_id          UUID REFERENCES clientes(id),
    origem              VARCHAR(20) NOT NULL DEFAULT 'CARDAPIO'
                        CHECK (origem IN ('CARDAPIO', 'MESA', 'IFOOD', 'BALCAO')),
    id_externo          VARCHAR(80),                          -- id do pedido no iFood (fase 2)
    tipo_entrega        VARCHAR(20) NOT NULL
                        CHECK (tipo_entrega IN ('RETIRADA', 'DELIVERY', 'CONSUMO_LOCAL')),
    status              VARCHAR(20) NOT NULL DEFAULT 'RECEBIDO'
                        CHECK (status IN ('AGUARDANDO_PAGAMENTO', 'RECEBIDO', 'EM_PREPARO',
                                          'PRONTO', 'SAIU_PARA_ENTREGA', 'ENTREGUE', 'CANCELADO')),
    endereco_entrega    JSONB,                                -- snapshot do endereço
    observacao          TEXT,
    subtotal            NUMERIC(10,2) NOT NULL,
    taxa_entrega        NUMERIC(10,2) NOT NULL DEFAULT 0,
    desconto            NUMERIC(10,2) NOT NULL DEFAULT 0,
    total               NUMERIC(10,2) NOT NULL,
    motivo_cancelamento TEXT,
    criado_em           TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (loja_id, numero),
    UNIQUE (loja_id, origem, id_externo),
    CHECK (total = subtotal + taxa_entrega - desconto),
    CHECK (tipo_entrega <> 'DELIVERY' OR endereco_entrega IS NOT NULL)
);
-- Tela da cozinha: pedidos abertos da loja, do mais antigo para o mais novo
CREATE INDEX idx_pedidos_loja_status ON pedidos(loja_id, status, criado_em);
CREATE INDEX idx_pedidos_cliente ON pedidos(cliente_id);

CREATE TABLE itens_pedido (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pedido_id       UUID NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    produto_id      UUID REFERENCES produtos(id),             -- referência; pode sumir
    nome_produto    VARCHAR(120) NOT NULL,                    -- snapshot
    preco_unitario  NUMERIC(10,2) NOT NULL,                   -- snapshot (já com promoção)
    quantidade      INT NOT NULL CHECK (quantidade > 0),
    observacao      TEXT,                                     -- "sem cebola"
    total_item      NUMERIC(10,2) NOT NULL                    -- (preço + adicionais) * qtd
);
CREATE INDEX idx_itens_pedido ON itens_pedido(pedido_id);

CREATE TABLE itens_pedido_adicionais (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_pedido_id  UUID NOT NULL REFERENCES itens_pedido(id) ON DELETE CASCADE,
    opcao_id        UUID REFERENCES opcoes_adicionais(id),
    nome_grupo      VARCHAR(80) NOT NULL,                     -- snapshot
    nome_opcao      VARCHAR(80) NOT NULL,                     -- snapshot
    preco           NUMERIC(10,2) NOT NULL,                   -- snapshot
    quantidade      INT NOT NULL DEFAULT 1 CHECK (quantidade > 0)
);
CREATE INDEX idx_adicionais_item ON itens_pedido_adicionais(item_pedido_id);

-- Linha do tempo do pedido (auditoria + "tempo em preparo" na cozinha)
CREATE TABLE historico_status_pedido (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pedido_id   UUID NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    status      VARCHAR(20) NOT NULL,
    usuario_id  UUID REFERENCES usuarios(id),                 -- null = sistema/cliente
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_historico_pedido ON historico_status_pedido(pedido_id, criado_em);

-- ---------------------------------------------------------------------
-- 6. PAGAMENTOS (via gateway — nunca guardar dados de cartão)
-- ---------------------------------------------------------------------
CREATE TABLE pagamentos (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pedido_id           UUID NOT NULL REFERENCES pedidos(id),
    metodo              VARCHAR(20) NOT NULL
                        CHECK (metodo IN ('PIX', 'CARTAO_ONLINE', 'DINHEIRO_NA_ENTREGA', 'CARTAO_NA_ENTREGA')),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDENTE'
                        CHECK (status IN ('PENDENTE', 'APROVADO', 'RECUSADO', 'EXPIRADO', 'ESTORNADO')),
    valor               NUMERIC(10,2) NOT NULL CHECK (valor > 0),
    troco_para          NUMERIC(10,2),
    gateway             VARCHAR(30),                          -- MERCADO_PAGO, ASAAS...
    gateway_id          VARCHAR(100),                         -- id da cobrança no gateway
    pix_copia_cola      TEXT,
    pix_expira_em       TIMESTAMPTZ,
    pago_em             TIMESTAMPTZ,
    criado_em           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (gateway, gateway_id)                              -- webhook idempotente
);
CREATE INDEX idx_pagamentos_pedido ON pagamentos(pedido_id);
