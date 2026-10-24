# Deploy: Supabase (banco) + Render (API) + Netlify (front)

Ordem: banco → API → loja → front → CORS. Cada etapa usa um dado da anterior.
Nenhuma senha ou chave vai para o git: tudo fica nas variáveis de ambiente de cada serviço.

## 1. Banco (Supabase)
1. supabase.com → **New project**.
   - Region: **East US (North Virginia)**. O Render não tem servidor no Brasil; a API faz várias consultas
     por pedido, então banco e API precisam estar perto um do outro.
   - Database Password: gerar e guardar num gerenciador de senhas.
2. **Connect** → **Session pooler** → copiar a string **JDBC**.
   - Session pooler (porta 5432) porque: a conexão direta é só IPv6, e o Transaction pooler (6543)
     não funciona com o Hibernate (prepared statements).
3. Não criar tabela: a API cria tudo no primeiro boot (Flyway), no schema `menuwin`.
   O `public` fica vazio de propósito, porque o Supabase expõe o `public` pela Data API dele.

## 2. API (Render)
1. render.com → **New** → **Blueprint** → repositório `menu-win`. O Render lê o `render.yaml`.
2. Ele pede os valores marcados `sync: false`:

   | Variável | Valor |
   |---|---|
   | `DB_URL` | a string JDBC **sem** usuário e senha: `jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require` (o host é o do seu projeto) |
   | `DB_USER` | `postgres.<id-do-projeto>` (está na string do Supabase) |
   | `DB_PASSWORD` | a senha do banco |
   | `CORS_ORIGENS` | por enquanto `https://exemplo.netlify.app`; trocar no passo 5 |
   | `URL_PUBLICA` | deixar vazio por enquanto; no passo 5 vira `https://menu-win-api.onrender.com` |

   `JWT_SECRET` e `CRYPTO_KEY` o Render gera sozinho. **Copiar o `CRYPTO_KEY` para o gerenciador de senhas**:
   se ele mudar, as credenciais do Mercado Pago salvas no banco viram lixo e cada loja precisa conectar de novo.
3. Esperar o deploy (o primeiro build leva uns 5 min). Conferir:
   `https://menu-win-api.onrender.com/actuator/health` → `{"status":"UP"}`.
   No log deve aparecer `profile is active: "prod"` e `Successfully applied 5 migrations to schema "menuwin"`.

## 3. Primeira loja
Supabase → **SQL Editor** → colar `docs/primeira-loja.sql`, trocar os valores marcados com `<<<` e rodar.
Depois apagar a consulta do histórico do editor (ela tem a senha em texto).
Conferir: `https://menu-win-api.onrender.com/api/public/lojas/<slug>/cardapio` responde com o nome da loja.

## 4. Front (Netlify)
1. netlify.com → **Add new site** → **Import an existing project** → repositório `menu-win-front`.
   O `netlify.toml` já diz o comando de build e a versão do Node.
2. **Environment variables**: `NEXT_PUBLIC_API_URL` = `https://menu-win-api.onrender.com` (sem barra no fim).
   Sem ela o build falha de propósito. Mudar depois exige novo deploy (o valor fica dentro do build).
3. Deploy. Anotar o endereço do site (ex.: `https://menu-win.netlify.app`).

## 5. Ligar um no outro
No Render → serviço → **Environment**:
- `CORS_ORIGENS` = endereço do site da Netlify, sem barra no fim. Com domínio próprio, os dois separados
  por vírgula.
- `URL_PUBLICA` = endereço da API no Render (é para onde o Mercado Pago manda o webhook).

Salvar (o Render reinicia a API). Teste final:
1. `https://<site>/painel`: entrar com o login criado no passo 3.
2. Cadastrar uma categoria e um produto.
3. Em outra aba (ou no celular), `https://<site>/<slug>`: fazer um pedido "pagar no caixa".
4. O pedido aparece na tela da cozinha sem recarregar.

## Limites dos planos grátis (ler antes de pôr loja de verdade)
- **Render free dorme após 15 min sem acesso.** O primeiro acesso depois disso leva ~1 min, e a tela
  da cozinha perde a conexão (ela reconecta sozinha quando a API acorda). Para loja de verdade:
  plano **Starter** (pago) no `render.yaml`.
- **Render: 512 MB de memória.** A imagem foi medida em ~440 MB sob carga (ver comentário no Dockerfile).
  Não subir a heap sem medir de novo.
- **Supabase free pausa o projeto após 7 dias sem uso** e não tem backup diário. Para loja de verdade: plano Pro.
- **Uma instância só da API**: a tela da cozinha (SSE) guarda as conexões em memória.
- **Pix em produção não foi testado** (ver pendências no CLAUDE.md). Até testar, a loja usa "pagar no caixa"
  e não conecta o Mercado Pago. O Pix simulado nunca liga em produção.

## Atualizar depois
- API: `git push` no `main` → o Render faz o deploy sozinho (`autoDeploy`). Migration nova roda no boot.
  Rodar `mvn test` antes: o build do Docker pula os testes.
- Front: `git push` no `main` → a Netlify faz o deploy sozinha.
