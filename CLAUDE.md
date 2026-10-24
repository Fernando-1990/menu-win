# Menu Win — contexto do projeto

## O que é
SaaS de cardápio digital e gestão de pedidos para restaurantes, com a marca de cada loja.
Modelo de negócio: mensalidade fixa, sem comissão por pedido (inspirado no SPKR Menu,
mas com marca, textos e visual próprios). Primeiro cliente-alvo: uma padaria que já usa iFood.

## Como trabalhar comigo
- Responder em português do Brasil.
- Seguir passo a passo, em ordem. Não pular etapas.
- Rodar `mvn test` depois de cada mudança e só seguir com os testes passando.

## Stack
- Backend: Java 21, Spring Boot 3.5, Spring Data JPA (Hibernate 6), PostgreSQL 16, Flyway
- Testes: JUnit 5 + Testcontainers (precisa do Docker rodando)
- Frontend (ainda não iniciado): Next.js — cardápio público (PWA), painel do lojista, tela da cozinha

## Comandos
- `docker compose up -d` — sobe o PostgreSQL (porta 5433 no host, para não brigar com um PostgreSQL local)
- `mvn test` — testes (inclui validação do mapeamento JPA contra o schema)
- `mvn spring-boot:run` — sobe a API com dados de exemplo (profile dev)
- `curl http://localhost:8080/api/public/lojas/padaria-demo/cardapio`
- `POST /api/auth/login` com `{"email","senha"}` → `GET /api/painel/me` com `Authorization: Bearer <token>`

## Decisões de arquitetura (manter)
- **Multi-tenant por coluna `loja_id`**, isolado com `@TenantId` do Hibernate.
  - `TenantContext` (ThreadLocal) guarda a loja da requisição; sempre limpar no `finally`.
  - Rotas públicas `/api/public/lojas/{slug}/**`: `TenantFilter` resolve a loja pelo slug.
  - Nunca preencher `lojaId` manualmente nem filtrar por loja em JPQL: o Hibernate faz.
  - Queries nativas NÃO são filtradas: filtrar `loja_id` explicitamente.
  - Sem loja definida, o resolver devolve UUID zerado (fail-safe: consultas vazias).
  - `Usuario` fica SEM `@TenantId` de propósito (login busca por e-mail, único global).
- `spring.jpa.open-in-view=false`; acesso ao banco só dentro de `@Transactional`.
- `ddl-auto=validate`: o schema é do Flyway. Mudança de banco = nova migration `V{n}__*.sql`.
- Pacotes por funcionalidade (`cardapio/`, `pedido/`...), não por camada.
- UUID como PK; dinheiro em `NUMERIC(10,2)` / `BigDecimal`.
- Pedido guarda snapshot de nome e preço (itens e adicionais). Mudar o cardápio não altera histórico.
- `pedidos.total = subtotal + taxa_entrega - desconto` (CHECK no banco; `Pedido.recalcularTotais()`).
- Número curto do pedido por loja vem de `lojas.proximo_numero_pedido` via `UPDATE ... RETURNING`.
  Essa coluna NÃO é mapeada na entidade `Loja` (evita sobrescrever o contador).
- Pedido em Pix nasce `AGUARDANDO_PAGAMENTO` e só vai para a cozinha após confirmação.
- Pagamentos via gateway (Mercado Pago, Asaas ou Pagar.me). Nunca guardar dados de cartão.
  `UNIQUE (gateway, gateway_id)` garante webhook idempotente.
- `origem` + `id_externo` em `pedidos` já preparados para a integração com o iFood.
- Autenticação (pacote `auth/`): JWT HS256 stateless via `spring-boot-starter-oauth2-resource-server`.
  - `POST /api/auth/login` → token com `sub` (usuário), `lojaId`, `papel`; validade `menuwin.jwt.expiracao` (8h).
  - `PainelTenantFilter` roda na cadeia do Security, após o `BearerTokenAuthenticationFilter`, e preenche
    o `TenantContext` com o `lojaId` do token. Não é `@Component` (evita registro duplo como filtro do servlet).
  - Claim `papel` vira `ROLE_DONO`/`ROLE_GERENTE`/`ROLE_COZINHA` (para `@PreAuthorize`/`hasRole`).
  - Rotas fora de `/api/public/**`, `/api/auth/login` e `/api/painel/**` são negadas por padrão.
  - `JWT_SECRET` (>= 32 bytes) é obrigatório fora do dev; a API não sobe sem ele.
  - Login responde 401 igual para e-mail inexistente, senha errada, usuário ou loja inativos.
  - Token não é revogável: desativar usuário só vale no próximo login (até 8h). Rever se precisar.
- CRUD do cardápio (`/api/painel/cardapio/**`, `CardapioPainelController`):
  - DONO e GERENTE em tudo; COZINHA só em `PATCH /produtos/{id}/disponibilidade` ("acabou hoje").
  - Referência a categoria/grupo vinda do request é SEMPRE carregada pelo repositório (filtro `@TenantId`);
    a FK sozinha não impede apontar para registro de outra loja. `findById` de outra loja devolve vazio (testado).
  - `OpcaoAdicional` não tem `loja_id`: só é acessada pelo grupo. Não criar endpoint de opção avulsa.
  - Produto: soft delete (`ativo=false`). Categoria: DELETE vira `ativa=false`, e dá 409 se tiver produto ativo.
    Grupo: DELETE apaga de verdade (opções e vínculos caem por cascade).
  - PUT do grupo recebe a lista completa de opções: com id atualiza, sem id cria, ausente remove.
    Ids das opções mantidas são preservados (carrinhos do passo 5 apontam para eles). Ordem = posição na lista.
  - `V2`: `itens_pedido(_adicionais)` → produto/opção com `ON DELETE SET NULL` (o snapshot fica).
- Erros da API em ProblemDetail (`spring.mvc.problemdetails.enabled`), mensagem em `detail`.
- Pedido pelo cardápio (`POST /api/public/lojas/{slug}/pedidos`, `PedidoPublicoService`):
  - Preço NUNCA vem do cliente. `totalEsperado` (opcional) diferente do calculado → 409.
  - Adicionais: opção precisa ser de um grupo do produto e estar disponível; soma das quantidades
    por grupo entre `minimo` e `maximo`; opção repetida no item = 400 (usar quantidade).
  - Número curto reservado por último no fluxo (`LojaRepository.reservarNumeroPedido`): trava a linha da loja.
  - Cliente via upsert nativo (`ClienteRepository.salvarPorTelefone`, `ON CONFLICT`): find-then-insert
    estourava a UNIQUE com dois pedidos simultâneos do mesmo cliente novo (pego no teste de concorrência).
  - Telefone normalizado para `55DDDNUMERO` (`Cliente.normalizarTelefone`).
  - Loja fechada → 409. Sem horários cadastrados = sempre aberta. Fuso fixo `America/Sao_Paulo` (`Loja.FUSO`).
    Horário que vira a noite (18:00–02:00) pertence ao dia em que abriu.
  - Na entrega (dinheiro/cartão) e `NO_CAIXA` nascem `RECEBIDO` (cozinha na hora); PIX nasce
    `AGUARDANDO_PAGAMENTO`; `CARTAO_ONLINE` dá 400.
  - Validação na ordem da tela: primeiro tipo de atendimento, depois forma de pagamento.
- Operação da loja (`GET/PUT /api/painel/loja/operacao`, só DONO, `OperacaoLojaService`):
  retirada, delivery, consumo no local, pagar no caixa, taxa, pedido mínimo, tempo de preparo.
  - `aceitaConsumoLocal` libera `CONSUMO_LOCAL` ("comer aqui") no cardápio.
  - `aceitaPagarNoCaixa` libera `NO_CAIXA`: vai para a cozinha e paga no balcão (qualquer meio). Não vale para delivery.
  - São independentes (dá para atender no local exigindo Pix antes). Ambos começam desligados (V4).
  - Pelo menos um atendimento (retirada, delivery ou consumo local) precisa ficar ligado.
  - Pagamento `NO_CAIXA` fica `PENDENTE` até alguém marcar como pago na tela da cozinha/balcão.
  - Mesas com QR e conta aberta por mesa continuam na fase 2.
- Status do pedido: SÓ via `Pedido.mudarStatus(novo, usuarioId)`, que grava o histórico junto (cascade).
  `status` não tem setter. `usuarioId` nulo = sistema/cliente.
- Cozinha/balcão (`/api/painel/pedidos/**`, todos os papéis; `PedidoCozinhaService`):
  - `GET /abertos`: RECEBIDO, EM_PREPARO, PRONTO, SAIU_PARA_ENTREGA + ENTREGUE ainda não pago em mãos
    (`pagamentoPendenteNoBalcao=true`, destacar). AGUARDANDO_PAGAMENTO não aparece.
  - `PATCH /{id}/status`: entre ativos e para ENTREGUE é livre (avançar, pular, voltar).
    SAIU_PARA_ENTREGA só delivery. AGUARDANDO_PAGAMENTO só sai pelo webhook (ou cancelamento).
    CANCELADO só DONO/GERENTE e com motivo. ENTREGUE/CANCELADO são finais. Repetir o mesmo status = ok.
  - `POST /{id}/pagamento/recebido`: só métodos pagos em mãos (`MetodoPagamento.recebidoPelaLoja()`).
    Idempotente. Pix online → 409 (o webhook confirma). Cancelar pedido com Pix APROVADO: estorno manual no MP.
  - `GET /eventos` (SSE): `CentralEventosCozinha`, conexões por loja, EM MEMÓRIA (1 instância da API;
    com várias, trocar por Postgres LISTEN/NOTIFY ou Redis). Eventos `conectado`, `pedido` (DTO completo,
    substituir pelo id) e ping a cada 25s. Sem replay: ao (re)conectar, recarregar `GET /abertos`.
  - Envio só DEPOIS do commit (`NotificadorCozinha` publica; `@TransactionalEventListener(AFTER_COMMIT)` envia).
    Toda mudança de pedido/pagamento deve chamar `notificador.pedidoMudou(pedido)` dentro da transação.
  - Autenticação do SSE pelo header Authorization. EventSource nativo não manda header: o frontend usa
    SSE via fetch (ex.: `@microsoft/fetch-event-source`). NUNCA token na URL.
  - Despacho ASYNC liberado no SecurityConfig (continuação de requisição já autorizada).
- `hibernate.default_batch_fetch_size=50`: coleções lazy em lote (lista da cozinha sem N+1).
  - Erros 409 = estado mudou (loja fechou, preço mudou); 400 = pedido inválido.
- Pix com Mercado Pago (pacote `pagamento/`):
  - O dinheiro cai na conta da PRÓPRIA loja: cada loja salva o access token dela
    (`PUT /api/painel/pagamentos/mercadopago`, só DONO). O Menu Win nunca fica com o dinheiro.
  - Token cifrado com AES-256-GCM (`shared/Cifrador`, chave `CRYPTO_KEY`, obrigatória fora do dev).
    Trocar a chave = perder os tokens salvos (as lojas precisam cadastrar de novo). Token nunca sai pela API.
  - `GatewayPagamento` é a interface; `MercadoPagoGateway` a implementação (RestClient). Outro gateway = outra classe.
  - Chamada ao gateway FORA de transação (`PixService` com `TransactionTemplate`): o pedido é commitado,
    depois o Pix é gerado, depois o `gateway_id` é salvo. Se o MP falhar: 502, pedido `CANCELADO`, pagamento `RECUSADO`.
  - `external_reference` = id do nosso `Pagamento`; também é o `X-Idempotency-Key` da criação.
  - Webhook em `/api/public/lojas/{slug}/webhooks/mercadopago` (slug → TenantFilter). O corpo NÃO é confiável:
    só o id é usado, e o pagamento é consultado na API do MP com o token da loja. Também confere valor,
    loja e `gateway_id`. Assinatura `x-signature` não é validada (a consulta ao MP já impede forjar aprovação).
  - Idempotente: linha do pagamento travada (`PESSIMISTIC_WRITE`); transições só PENDENTE→final e APROVADO→ESTORNADO.
  - Aprovado → pedido `RECEBIDO` (cozinha). Recusado/expirado → `CANCELADO` com motivo. Aprovado em pedido
    já cancelado: só loga (estorno manual).
  - MP exige e-mail do pagador: Pix sem e-mail do cliente dá 400.
  - `GET /api/public/lojas/{slug}/pedidos/{id}`: status para o cliente (polling na tela do Pix).
  - `URL_PUBLICA` (https) define a notification_url; vazia = sem webhook (dev local precisa de túnel, ex. ngrok).
- Cardápio público expõe grupos (só opções disponíveis) e dados do checkout (`aberta`, entrega, `aceitaPix`, taxa, mínimo).
  Produto com grupo obrigatório sem opções suficientes aparece com `disponivel=false`.

## Roadmap
### Fase 1 — MVP
1. [x] Modelagem do banco (`V1__schema_inicial.sql`)
2. [x] Estrutura do projeto, entidades JPA, multi-tenant, endpoint público do cardápio
3. [x] Autenticação do painel: Spring Security + JWT com `lojaId` no token,
       preenchendo o `TenantContext` nas rotas `/api/painel/**`
4. [x] CRUD do cardápio no painel (categorias, produtos, adicionais)
5. [x] Criação de pedido pelo cardápio público (validação de adicionais, número curto)
6. [x] Pagamento Pix via gateway + webhook (Mercado Pago)
7. [x] Tela da cozinha em tempo real (SSE)
8. [ ] Frontend Next.js

### Fases seguintes
- Fase 2: integração iFood (exige homologação), mesas com QR code
- Fase 3: setores e impressão, app do garçom, delivery/motoboy, cupons e fidelidade, WhatsApp
- Fase 4: estoque por XML de nota, NFC-e (via API terceira), financeiro

## Armadilhas já encontradas
- Recurso velho em `target/classes`: o zip original veio com datas no futuro (relógio adiantado), e o
  Maven só copia recurso não filtrado (ex.: `.sql`) quando o original é mais novo que a cópia. Resultado:
  edição no seed não chegava na API. Se algo "não mudou" ao rodar, `mvn clean` primeiro.
  `SeedDevTest` cobre o seed de dev (os outros testes não o carregam).

## Status
Passos 1 a 7 entregues (+ consumo no local / pagar no caixa), `mvn test` passando (44 testes, PostgreSQL real via Testcontainers).
- Testcontainers fixado em 1.21.4 no `pom.xml`: o 1.21.0 do Boot 3.5.0 não conversa com Docker 29+.
- Login de exemplo do dev está no comentário de `db/dev/R__seed_dev.sql`.
- Erros de validação (400) ainda não listam os campos no ProblemDetail; o frontend vai precisar.
- Pendências do pedido público (antes de ir para produção):
  - Sem idempotência: retry de rede pode criar pedido duplicado (pensar em `Idempotency-Key`).
  - Sem rate limit na rota pública de pedido.
- Pendências do Pix:
  - NÃO testado contra o sandbox real do MP (testes usam servidor simulado). Fazer com credenciais TEST-.
  - Sem job para expirar Pix se o webhook nunca chegar (hoje depende do "cancelled" do MP).
  - Sem validação da assinatura `x-signature` do webhook (defesa extra; ver decisão acima).
- Pendências da cozinha:
  - Não registra quem recebeu o pagamento nem o meio usado no caixa (dinheiro/cartão/Pix): fechamento de caixa (fase 4).
  - SSE em memória: não escala para mais de uma instância (ver decisão acima).
Próximo: passo 8 (frontend Next.js: cardápio público PWA, painel do lojista, tela da cozinha).
