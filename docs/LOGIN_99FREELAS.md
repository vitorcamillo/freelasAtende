# Login 99Freelas — fluxo manual com cookies

O 99Freelas pode exigir verificação humana no login; por isso a sessão é obtida no navegador do dono e o FreelasAtende reaproveita os cookies.

Depois do login, o FreelasAtende funciona reaproveitando os cookies exportados.

## Passo a passo

1) Abra o Chrome/Edge normal (não automatizado) e acesse `https://www.99freelas.com.br/login`

2) Faça login normalmente (conclua a verificação, se aparecer).

3) Exporte os cookies do domínio `www.99freelas.com.br`.
   - Opção mais simples: usar uma extensão como **EditThisCookie** para exportar em JSON.
   - Garanta que o JSON tenha pelo menos os cookies de sessão (ex: `JSESSIONID`, `sgcn` e afins) e, se existir, cookies do Cloudflare (ex: `cf_clearance`, `__cf_bm`).

4) Envie o JSON de cookies para o FreelasAtende (uma das opções):

### Opção A — via Telegram

No chat do bot:

`/cookies {"JSESSIONID":"...","sgcn":"...","cf_clearance":"..."}`

### Opção B — via HTTP (API local)

```bash
curl -sS -X POST "http://localhost:8080/api/auth/cookies" \
  -H "Content-Type: application/json" \
  -d '{"JSESSIONID":"...","sgcn":"...","cf_clearance":"..."}'
```

5) Verifique se a sessão ficou válida:

```bash
curl -sS "http://localhost:8080/api/auth/session"
curl -sS "http://localhost:8080/api/status"
```

## Onde os cookies ficam salvos

- `data/cookies.json`

Recomendação: não commitar esse arquivo em Git.
