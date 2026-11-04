# 99Freelas - extras encontrados na captura de 2026-04-21

Itens que apareceram na sessao capturada e nao estavam claros no mapeamento inicial.

## Fluxos extras relevantes

- MFA de sessao:
  - `GET /mfa/session/confirm/{id}`
  - `POST /services/user/confirmarMfaSessao/{id}` com body JSON `{"code":"683975"}`
- WebSocket de tempo real:
  - `wss://ws.99freelas.com.br:3000/socket.io/?idPessoa=269409&userSessionId=...`
- Listagem de mensagens usa polling incremental:
  - `listarMensagensConversa` com `dhCorte` variando
  - `carregarConversas` tambem aceita `idConversa` para refresh pontual

## Formatos observados

- Respostas de servicos internos (`/services/...`) chegam como `text/plain` com JSON URL-encoded.
- `POST /services/user/enviarMensagemConversa` usa `application/x-www-form-urlencoded` com `data=` duplamente codificado.
- `POST /services/project/enviarPerguntaParaCliente/{id}` usa JSON com `existeConversa` e `confirmarAcao`.
- `POST /services/project/enviarProposta` usa JSON com:
  - `idProjeto`
  - `oferta`
  - `duracaoEstimada`
  - `proposta`
  - `valorMinimoCents`
  - `novaProposta`
  - `promover`

## Endpoints de suporte revelados pelo JS

Catalogo visto em `main.js`:

- `services/user/verificarPessoaLogada`
- `services/consultas/listarNovasMensagens`
- `services/consultas/listarNovasMensagensDeDisputa`
- `services/consultas/listarNovasNotificacoes`
- `services/consultas/listarNovosProjetosInteressantes`
- `services/project/getProjetosInteressantes`
- `services/project/getMinhasPropostas`
- `services/project/getMeusProjetos`
- `services/user/adicionarProjetoFavorito`
- `services/user/removerProjetoFavorito`
- `services/user/marcarTodasAsConversasComoVisualizadas`

## Implicacoes para o produto

- O modo `LIVRE` ja pode operar so via HTTP para proposta, pergunta e resposta em conversa.
- O modo `REGULAR` pode usar a inbox real para pedir sua aprovacao so quando necessario.
- O modo `ENSINAMENTO` pode registrar contexto a partir das conversas reais e do polling incremental.
