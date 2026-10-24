# Menu Win API

## Rodar localmente
```bash
docker compose up -d          # PostgreSQL 16
mvn spring-boot:run           # sobe na 8080, aplica migrations + dados de exemplo (profile dev)
curl http://localhost:8080/api/public/lojas/padaria-demo/cardapio
```

## Pedido pelo cardápio público
`POST /api/public/lojas/{slug}/pedidos` (sem login). Exemplo:
```json
{
  "tipoEntrega": "RETIRADA",
  "cliente": { "nome": "Maria", "telefone": "(11) 98888-7777" },
  "metodoPagamento": "CARTAO_NA_ENTREGA",
  "itens": [
    { "produtoId": "<id>", "quantidade": 2, "observacao": "sem cebola",
      "adicionais": [ { "opcaoId": "<id>", "quantidade": 1 } ] }
  ],
  "totalEsperado": "52.00"
}
```
Delivery: `"tipoEntrega": "DELIVERY"` + `"endereco": {logradouro, numero, bairro, cidade, uf, cep}`.
Dinheiro: `"metodoPagamento": "DINHEIRO_NA_ENTREGA"` e, se precisar, `"trocoPara": "50.00"`.
Comer aqui e pagar ao sair: `"tipoEntrega": "CONSUMO_LOCAL"` + `"metodoPagamento": "NO_CAIXA"`
(cada opção precisa estar ligada pelo dono em `PUT /api/painel/loja/operacao`).
Pix: `"metodoPagamento": "PIX"` (exige `cliente.email`). A resposta traz `pix.copiaECola` e `pix.qrCodeBase64`;
acompanhe em `GET /api/public/lojas/{slug}/pedidos/{id}` até `status` virar `RECEBIDO`.

## Pix simulado (homologar sem conta no Mercado Pago)
No profile dev o Pix vem **simulado** (log: `PIX SIMULADO ATIVO`). Nada é cobrado de verdade:
1. No painel (DONO), em Configurações, conecte o Pix com qualquer texto (ex.: `teste`).
2. Faça um pedido com Pix pelo cardápio. A tela do pedido mostra **Simular pagamento aprovado / Pix expirado**.
3. O botão segue o mesmo caminho do webhook real: aprovado vai para a cozinha; expirado cancela.

Pela API: `POST /api/public/lojas/{slug}/simulador-pix/{pedidoId}?status=APROVADO`.
Só funciona nos profiles dev/test (em outro profile a API nem sobe com ele ligado).
Para testar no sandbox real do Mercado Pago, suba com `PIX_SIMULADO=false`.

## Pix (Mercado Pago)
1. Crie uma aplicação em https://www.mercadopago.com.br/developers e copie o **access token de teste** (`TEST-...`).
2. Faça login no painel como DONO e salve o token:
   `PUT /api/painel/pagamentos/mercadopago` com `{"accessToken": "TEST-..."}`
3. Para receber webhooks localmente, exponha a API por um túnel https (ex.: `ngrok http 8080`)
   e suba com `URL_PUBLICA=https://<seu-tunel>`.

| Variável | Para quê | Fora do dev |
|---|---|---|
| `JWT_SECRET` | assinatura dos tokens do painel (>= 32 bytes) | obrigatória |
| `CRYPTO_KEY` | cifra dos tokens de gateway (32 bytes em Base64) | obrigatória |
| `URL_PUBLICA` | base https para o webhook do Mercado Pago | recomendada |

## Painel (autenticação)
```bash
# login de exemplo do dev: veja o comentário em src/main/resources/db/dev/R__seed_dev.sql
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
     -d '{"email":"<email>","senha":"<senha>"}'
curl http://localhost:8080/api/painel/me -H "Authorization: Bearer <token>"
```
Fora do profile dev, defina `JWT_SECRET` (mínimo 32 bytes).

### Cozinha e balcão (`/api/painel/pedidos`, todos os papéis)
| Rota | O que faz |
|---|---|
| `GET /abertos` | Pedidos em andamento + entregues ainda não pagos no caixa |
| `GET /eventos` | SSE em tempo real (`conectado`, `pedido`). Use SSE via fetch: precisa do header `Authorization` |
| `PATCH /{id}/status` | `{"status": "EM_PREPARO"}`; cancelar: `{"status": "CANCELADO", "motivo": "..."}` (DONO/GERENTE) |
| `POST /{id}/pagamento/recebido` | Marca como pago o pagamento feito em mãos (caixa ou entregador) |

### Operação da loja (`/api/painel/loja/operacao`, só DONO)
`GET` / `PUT` com `aceitaRetirada`, `aceitaDelivery`, `aceitaConsumoLocal`, `aceitaPagarNoCaixa`,
`taxaEntregaPadrao`, `pedidoMinimo`, `tempoPreparoMin`.

### Cardápio no painel (`/api/painel/cardapio`, papéis DONO e GERENTE)
| Recurso | Rotas |
|---|---|
| Categorias | `GET/POST /categorias`, `GET/PUT/DELETE /categorias/{id}` |
| Produtos | `GET /produtos?categoriaId=`, `POST /produtos`, `GET/PUT/DELETE /produtos/{id}` |
| "Acabou hoje" | `PATCH /produtos/{id}/disponibilidade` (também COZINHA) |
| Grupos de adicionais | `GET/POST /grupos`, `GET/PUT/DELETE /grupos/{id}` (opções vão dentro do grupo) |

## Testes (precisa do Docker rodando)
```bash
mvn test
```

## Estrutura (pacote por funcionalidade)
```
br.com.menuwin
├── tenant/      isolamento entre lojas (TenantContext, resolver do Hibernate, filtro)
├── auth/        login, JWT, Spring Security, loja do token no TenantContext
├── shared/      tipos comuns (Endereco)
├── loja/        loja, identidade visual, horários
├── usuario/     usuários do painel
├── cardapio/    categorias, produtos, adicionais + cardápio público
├── cliente/
├── pedido/
└── pagamento/
```

## Regras do multi-tenant
1. Entidade de negócio com `loja_id` -> campo `@TenantId private UUID lojaId;`
2. Nunca preencher `lojaId` na mão: o Hibernate preenche a partir do `TenantContext`.
3. Query nativa NÃO é filtrada automaticamente: filtrar por `loja_id` explicitamente.
4. `open-in-view` desligado: acesso ao banco sempre dentro de `@Transactional`.
