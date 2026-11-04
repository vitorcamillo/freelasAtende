# Freelancer.com.br — captura manual de cookies (memoria permanente)

O freelancer.com.br mantem o site autenticado atras do Cloudflare. O sistema nao
tenta contorna-lo: a sessao vem de cookies capturados pelo dono no navegador dele.

## O que precisa de cookie e o que nao precisa

- **Discovery (descobrir projetos novos): usa a listagem publica `/projetos`.**
  A leitura acontece num Chrome real que so espera a verificacao liberar; se nao
  liberar, o ciclo e pulado.
- **Inbox / mensagens autenticadas: precisa de cookie.** A area logada continua
  atras do Cloudflare, entao a sessao vem de captura manual do usuario (abaixo) e o
  sistema reusa os cookies.

## Quando renovar

Quando a sessao expira, o polling detecta `verificarLogin()==false` e o sistema
**avisa no Telegram** ("Sessao Freelancer.com.br expirada — envie novos cookies").
Ao receber esse aviso, o usuario refaz a captura abaixo e me pede para atualizar.

## Como capturar (2 minutos, no navegador normal do usuario)

1. Abrir https://www.freelancer.com.br e fazer login (passar o Cloudflare como humano).
2. Confirmar que esta logado (aparece o nome/avatar, nao "Conecte-se").
3. Abrir o DevTools (F12) → aba **Application** (ou "Aplicativo") → **Cookies** →
   `https://www.freelancer.com.br`.
4. Copiar os cookies essenciais (nome=valor). Os que importam para a sessao:
   - `freelancer_live_session` (sessao autenticada)
   - `cf_clearance` (passe do Cloudflare — expira rapido, ~30 min a algumas horas)
   - `XSRF-TOKEN` (token anti-CSRF para acoes)
   - `GETAFREE_AUTH_HASH_V2` / `GETAFREE_AUTH_HASH` se existirem

   Alternativa rapida: no **Console** do DevTools, rodar `document.cookie` e copiar
   tudo (nao pega httpOnly, mas costuma bastar `cf_clearance` + XSRF; se faltar a
   sessao, use a aba Application que mostra os httpOnly).

5. Me enviar o texto colado. Eu gravo em `data/freelancerbr-cookies.json` no formato
   `{ "nome": "valor" }` (arquivo gitignored, permissao 600) e valido a sessao.

## Formato do arquivo

```json
{
  "freelancer_live_session": "...",
  "cf_clearance": "...",
  "XSRF-TOKEN": "..."
}
```

## Observacao sobre o `cf_clearance`

O `cf_clearance` esta atrelado ao seu IP e User-Agent e expira mais rapido que a
sessao. Se o discovery/inbox comecar a retornar a pagina de desafio do Cloudflare
mesmo com `freelancer_live_session` valido, e o `cf_clearance` que caiu — basta
recapturar. O sistema trata a pagina de desafio como "sessao invalida" e avisa.

Observacao: a expiracao dos cookies afeta a **inbox** por HTTP; o discovery usa
a listagem publica `/projetos` no Chrome e o ciclo e pulado quando a verificacao
nao libera.
