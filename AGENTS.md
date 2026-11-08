# FreelasAtende — notas operacionais para o agente

## Plataformas integradas

| Plataforma | Login | Sessao | Capacidades |
|---|---|---|---|
| 99Freelas | Cookies manuais (desafio exige humano) | cookies auto | discovery, inbox, msg, pergunta, proposta |
| Workana | cookies | cookies | discovery, inbox, msg, pergunta |
| Upwork | Auto (driverless + senha + pergunta seguranca + OTP Gmail) | cookies auto | discovery, inbox, msg, pergunta |
| Freelancer.com | Auto (driverless) | cookies auto | discovery, inbox, msg, pergunta |
| Freelancer.com.br | Discovery: **Auto** (listagem `/projetos` via Chrome real). Inbox: **MANUAL** (Cloudflare) | cookies manuais | discovery, inbox, msg, pergunta, proposta |

## Freelancer.com.br — discovery via Chrome + inbox por cookie (IMPORTANTE)

A area autenticada e a listagem de projetos podem apresentar o **Cloudflare
Managed Challenge**. O comportamento pode variar. A estrategia e dividida:

- **Discovery = listagem `/projetos` pelo Chrome real.** O .com.br tem catalogo
  proprio (ids fora da API global), entao `FreelancerBrBrowser.baixarListagemProjetos`
  abre `/projetos` (unica rota sob o desafio) com os cookies injetados e o
  `FreelancerBrMarketplaceAdapter` extrai os cards. Envio e inbox seguem por HTTP.
- **Inbox/mensagens = cookie manual.** A area logada continua atras do Cloudflare.
  Quando a sessao expira, o polling detecta pela inbox e **avisa no Telegram**
  ("Sessao Freelancer.com.br expirada"). O usuario faz login no navegador e **pede
  ao agente para atualizar os cookies**.
- **Como capturar** os cookies: seguir `docs/COOKIES_FREELANCER_BR.md`.
- **Onde salvar**: `data/freelancerbr-cookies.json`, formato `{ "nome": "valor" }`
  (gitignored). Cookies essenciais: `freelancer_live_session`, `cf_clearance`,
  `XSRF-TOKEN`.
- **Como validar**: `verificarLogin()` baixa `/account/inbox`; se voltar a pagina
  do Cloudflare ou "Conecte-se", a sessao (da inbox) esta invalida. O discovery
  usa os mesmos cookies no Chrome.

O `cf_clearance` expira mais rapido que a sessao (atrelado a IP+User-Agent) e afeta
apenas a inbox. Se a inbox voltar a bater no desafio mesmo com
`freelancer_live_session` valido, e o `cf_clearance` que caiu — recapturar.

### Inbox/mensagens do .com.br

**Mapa do site autenticado (confirmado na sessao logada da conta do operador):**

| Area | Caminho |
|---|---|
| Painel | `/account/dashboard` (redireciona p/ `/account/dashboard/professional`) |
| Inbox / mensagens | `/account/inbox` (abas Mensagens/Arquivado; colunas Data, Contato, Mensagem) |
| Meus projetos | `/account/projects` (Proposta enviada / Em andamento / Completos) |
| Perfil / config | `/account` (secoes: Meu resumo, Referencias, **Portfolio**, imagem) |
| Rede | `/account/network` |
| Transacoes | `/account/transactions` |
| Configuracoes | `/account/settings` |
| Sair | `/account/logout` |

Projetos publicos: `/projetos`; projeto individual:
`/projetos/{categoria}/{sub}/{slug}-{ID_ALFANUM}` (ID ex.: `3BOPS371`).

Inbox HTML: tabela com abas Mensagens/Arquivado e colunas Data/Contato/Mensagem.
Conta nova pode estar vazia ("Voce nao tem..."). Capabilities ja habilitadas no
`FreelancerBrMarketplaceAdapter`: `discovery`, `inbox`, `perguntas` (primeiro
contato via "Estou interessado") e `propostas`.

## Credenciais e segredos

- Tudo em `.env` (gitignored, permissao 600): credenciais das plataformas,
  `UPWORK_SECURITY_ANSWER`, `GMAIL_*` (OTP read-only), cookies em `data/*cookies*.json`.
- Nunca commitar segredo; nunca exibir valor de cookie/token/senha em log ou output.

## Login assistido / Xvfb

- Em producao o Chrome roda headful sob Xvfb (`:99`), como no Dockerfile.
- JavaDriverless: usar `JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS=true` para
  destravar profile apos restart/crash.
