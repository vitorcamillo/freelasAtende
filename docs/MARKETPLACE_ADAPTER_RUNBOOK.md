# Runbook: adicionando um novo marketplace

Este runbook descreve o caminho seguro para adicionar outra plataforma de freelas sem misturar dados, sessoes ou envios com o 99Freelas.

## 1. Escolha e capability map

Antes de codigo, documente:

- nome da plataforma e chave interna em `PlataformaFreela`;
- como autentica;
- como lista oportunidades;
- como le detalhes do projeto;
- como le inbox/conversas;
- se permite pergunta, proposta, resposta e cancelamento;
- limites de quota/rate limit;
- quais operacoes devem ficar `OPERACAO_INDISPONIVEL`;
- quais variaveis/volumes serao necessarios no Coolify.

Nao implemente escrita se a leitura, login e limites ainda nao foram validados.

## 2. Modelo de dados

Todo dado externo precisa carregar:

- `plataforma`;
- `idExterno`;
- `id` interno via `PlataformaFreela.idInterno(idExterno)`.

Isto evita colisao quando duas plataformas usam o mesmo ID nativo.

## 3. Adapter

Crie um `MarketplaceAdapter` pequeno em `src/main/java/com/freelasatende/service/impl/`.

Regras:

- `plataforma()` retorna a enum correta.
- `capabilities()` reflete apenas o que foi validado.
- operacoes nao suportadas lancam `MarketplaceAdapterException` com `OPERACAO_INDISPONIVEL`.
- leitura read-only deve funcionar antes de qualquer escrita.
- chamadas externas devem ter erro normalizado por plataforma.

## 4. Configuracao

Ative explicitamente pelo Coolify:

```env
MARKETPLACES_ENABLED=99FREELAS,NOVA_PLATAFORMA
```

Durante validacao, prefira:

```env
REAL_WRITE_ENABLED=false
REAL_WRITE_SIMULATE=true
TELEGRAM_SIMULATE=true
MODO_DEFAULT=ENSINAMENTO
```

## 5. Testes minimos

Adicione testes para:

- registry resolve o adapter;
- `marketplaces.enabled` filtra o adapter;
- discovery/read-only retorna IDs de plataforma corretos;
- cada escrita nao suportada falha fechada;
- uma falha nessa plataforma nao bloqueia outra plataforma ativa;
- dashboard/Telegram mostram o label da plataforma.

## 6. Validacao antes de escrita

So habilite escrita quando todos forem verdadeiros:

- login/sessao reproduzivel sem segredo em Git;
- leitura de projetos e inbox validada;
- parser nao mistura cliente/freelancer;
- quota/limites entendidos;
- envio simulado revisado no dashboard e Telegram;
- aprovacao humana obrigatoria preservada;
- rollback claro: remover a plataforma de `MARKETPLACES_ENABLED`.

## 7. Rollout

1. Deploy com a plataforma em read-only.
2. Observe logs e dashboard.
3. Rode uma sincronizacao manual.
4. Confira pendencias no Telegram com filtro da plataforma.
5. Mantenha `REAL_WRITE_ENABLED=false`.
6. Depois de validado, habilite uma unica operacao de escrita por vez.

## 8. Rollback

Para desabilitar imediatamente:

```env
MARKETPLACES_ENABLED=99FREELAS
```

Redeploy/restart. As acoes ja criadas continuam no banco com sua plataforma, mas novas leituras da plataforma removida param.

## 9. Workana read-only

A captura MegaCapture `session_2026-05-29T14-58-32-930Z` validou endpoints de leitura da Workana:

- `GET /jobs` e `GET /pt/jobs` retornam JSON com `slug`, `title`, `description`, `budget`, `skills`, `rating`, `totalBids`, `hasThread`, `hasContacted` e `inboxUrl`;
- `GET /saved_searches/1` e `GET /dashboard/recommended_projects` tambem retornam JSON util;
- endpoints de inbox existem, mas dependem de sessao autenticada;
- endpoints de permissao/escrita responderam de forma inconsistente na captura, incluindo `403` em permissao.

Por isso, `WORKANA` deve iniciar somente com discovery read-only:

```env
MARKETPLACES_ENABLED=99FREELAS,WORKANA
WORKANA_URL_BASE=https://www.workana.com
WORKANA_MAX_PROJETOS=20
WORKANA_COOKIES_PATH=/app/data/workana-cookies.json
WORKANA_WORKER_SLUG=vitor-camillo
REAL_WRITE_ENABLED=false
REAL_WRITE_SIMULATE=true
```

Pergunta inicial no Workana usa o mesmo core de `ENVIAR_PERGUNTA` das demais plataformas. Para envio real, mantenha cookies autenticados do domínio `www.workana.com` em `WORKANA_COOKIES_PATH`; o cliente busca o form `/messages/bid/{slug}/?tab=message`, extrai os campos CSRF e envia `POST /messages/index/{slug}/{workerSlug}` conforme o form retornado. `WORKANA_WORKER_SLUG` é fallback quando o form não trouxer a action completa.

Nao habilite proposta, pergunta ou resposta na Workana ate haver uma captura separada de escrita simulada/real aprovada e revisada. O adapter atual falha fechado para qualquer escrita.
