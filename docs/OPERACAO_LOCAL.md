# Operacao Local

Este projeto assume operacao local. A seguranca principal nao e autenticacao de rede; e controle explicito de modo, escrita real e revisao humana quando o modo exigir.

## Modos

- `ENSINAMENTO`: trava envio direto ao cliente. O sistema cria rascunhos, pede contexto e mantem a acao pendente para revisao/ensino.
- `REGULAR`: opera com revisao quando houver duvida. Pode enviar a abordagem inicial curta automaticamente se `real.write.enabled=true`.
- `LIVRE`: modo liberado. Pode enviar a abordagem inicial curta automaticamente se `real.write.enabled=true`.

## Escrita

- `REAL_WRITE_ENABLED=false` e `REAL_WRITE_SIMULATE=false`: nenhum envio real e nenhum envio simulado.
- `REAL_WRITE_ENABLED=false` e `REAL_WRITE_SIMULATE=true`: registra envio simulado, sem chamada real ao cliente.
- `REAL_WRITE_ENABLED=true`: permite escrita real para os fluxos que o modo atual liberar.

## Abordagem inicial automatica

Em projetos novos que ainda nao foram processados, quando a IA avaliar que o projeto e atendivel, o sistema usa a abordagem curta:

```text
Ja foi atendido?
```

Essa mensagem so pode ser enviada automaticamente em `REGULAR` ou `LIVRE` com `REAL_WRITE_ENABLED=true`. Antes do envio real, o sistema precisa confirmar:

- limite diario de conexoes disponivel;
- ausencia de uma acao pendente ja aberta para o projeto;
- validacao do rascunho pela guarda de mensagem.

Reputacao baixa ou ausencia de nota do cliente nao bloqueia essa abordagem inicial. A mensagem curta serve para confirmar se ainda existe abertura antes de gastar proposta completa.

A reserva minima `sistema.min-conexoes-reserva` vale para propostas, nao para a abordagem inicial curta. Se o limite diario impedir o envio, a abordagem nao vira pendencia humana: ela aguarda nova disponibilidade para tentativa automatica. Nao e feito envio real nem registro de uso de conexao nesse caso.

### Triagem de capacidade

A avaliacao da abordagem inicial decide se vale abrir conversa, nao se o projeto ja esta pronto para proposta. Projetos de desenvolvimento web compativeis, como site, blog, WordPress, app web ou SaaS, podem receber a pergunta curta mesmo quando ainda precisarem de detalhamento de escopo, SEO/RSS, assinatura ou integracao web usual.

O filtro continua recusando automaticamente demandas com bloqueadores criticos, como app nativo, `n8n`/RD Station, hardware, biometria, automacao juridica/financeira critica ou seguranca sensivel.

### Recuperar projeto ignorado

Quando uma auditoria confirmar que um projeto foi ignorado por uma regra antiga, ele pode ser reavaliado pontualmente sem apagar o historico:

```bash
curl -X POST http://127.0.0.1:18084/api/controle/projetos/ID_DO_PROJETO/reprocessar
```

O endpoint aceita apenas projeto atualmente `IGNORADO`, descarta a avaliacao em cache e usa o modo operacional atual. Em `REGULAR` ou `LIVRE`, uma abordagem aprovada pode ser enviada imediatamente se a escrita real estiver ligada; em `ENSINAMENTO`, ela fica pendente. O guardiao antirrepeticao e o limite diario de conexoes continuam valendo.

## Operacao segura

Use `scripts/start-safe.sh` para subir com escrita real desligada, Telegram simulado, scheduler desligado e perfil `local-h2`.

O status deve ser conferido por:

```bash
scripts/status-safe.sh
```

Se o processo tiver sido iniciado com `PORT` ou `SESSION_NAME` customizados, o script usa `target/runtime/freelas-safe.pid` e `target/runtime/freelas-safe.env` para encontrar a porta e a sessao corretas.

## Prioridades de manutencao

1. Manter scripts locais previsiveis: `tmux` por nome exato, porta real detectada e parada limpa.
2. Deixar dashboard e docs sempre alinhados com a regra de modo/escrita.
3. Reduzir classes grandes aos poucos, com extracoes pequenas e testadas antes de mover fluxo critico.
