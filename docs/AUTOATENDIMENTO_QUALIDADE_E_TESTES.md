# Autoatendimento: qualidade, segurança e testes

Objetivo: deixar o atendimento natural, útil e confiável. Em `ENSINAMENTO`, nada vai ao cliente sem revisão/ensino humano. Em `REGULAR` e `LIVRE`, a abordagem inicial curta pode sair automaticamente quando a escrita real estiver explicitamente ligada. O sistema deve evitar tom de sistema, spam, promessa indevida, contato fora da plataforma e respostas que ignorem o histórico.

Limite ético adotado: o sistema pode soar natural e profissional, mas não deve tentar enganar a pessoa nem criar mecanismos de disfarce. A confiança vem de revisão humana, contexto, guardião e rastreabilidade.

## Camadas de proteção

1. Guardião de saída
   - Bloqueia termos de teste, sistema, automação, IA, prompt e mensagem gerada automaticamente.
   - Bloqueia contato ou pagamento fora da plataforma.
   - Bloqueia listas numeradas, bullets, templates repetidos e excesso de perguntas.
   - Bloqueia mensagens longas demais.
   - Bloqueia segundo rascunho pendente para o mesmo projeto ou conversa.
   - Permite reescrita manual com override, mas ainda valida tom e segurança.

2. Compositor humano
   - Máximo de perguntas essenciais.
   - Sem lista numerada por padrão.
   - Sem fechamento repetido.
   - Mensagem muda conforme primeiro contato, resposta de inbox ou follow-up.
   - Usa histórico antes de sugerir resposta.

3. Controle operacional
   - Dashboard mostra texto ao cliente, motivo interno, guardião, risco, bloqueios e histórico.
   - Telegram mostra resumo curto e botões de decisão.
   - Em `ENSINAMENTO`, aprovar depende de conversa/revisão de ensino antes de envio.
   - Em `REGULAR`/`LIVRE`, abordagem inicial curta pode ser automática se `real.write.enabled=true`.
   - Reescrever nunca envia.

4. Testes de IA
   - Testes com fake IA validam contrato sem depender de rede.
   - Teste com IA real fica opt-in (ex.: `*RealIT` no perfil `real-ia-tests`).

5. Qualidade operacional
   - Cada rascunho recebe score de naturalidade, clareza, risco, duplicidade e confiança.
   - A decisão de qualidade é `APROVAR`, `REESCREVER` ou `BLOQUEAR`.
   - Dashboard e Telegram mostram score, sinais e comentário de qualidade.
   - O gerador de proposta/inbox cai para fallback seguro quando a qualidade não aprova.

6. Auditor IA
   - `AvaliadorIaService.auditarRascunhoCliente` usa a IA configurada como revisora.
   - O auditor nunca substitui o guardião determinístico; se a regra local bloqueia, a IA nem é chamada.
   - A auditoria é para qualidade profissional, não para camuflagem ou engano.

7. Manual de voz
   - O arquivo `data/manual-voz-freelancer.md` guia propostas, perguntas e inbox.
   - Ele define tom, estrutura, termos proibidos e exemplos bons.

## Matriz de testes a manter crescendo

- Naturalidade: mensagens curtas, sem lista, sem tom formal rígido.
- Segurança: sem contato externo, sem pagamento externo, sem promessa garantida.
- Anti-spam: repetição por projeto, conversa e fingerprint.
- Contexto: inbox só responde quando há mensagem nova do cliente.
- Modos: `ENSINAMENTO` trava envio direto; `REGULAR`/`LIVRE` podem enviar abordagem curta só com escrita real ligada.
- Triagem inicial: web/SaaS/blog/WordPress compativeis nao sao descartados apenas por escopo incompleto; biometria, n8n, nativo e dominio sensivel permanecem bloqueados.
- Recuperacao: reprocessamento pontual de `IGNORADO` preserva historico e continua passando por deduplicacao e limite de conexoes.
- Reescrita: bloqueia termos ruins, atualiza payload seguro e mantém pendente.
- Dashboard: overview, fila, detalhe, modal e escape de HTML.
- Telegram: pendências, bloqueios, detalhe, reescrita e callbacks.
- IA: respostas reais passam por guardião antes de virar ação.
- Regressão visual/operacional: inbox manual, logs escapados, sem polling agressivo.

## Próximos incrementos úteis

- Histórico comparativo por cliente/projeto para evitar repetição semântica.
- Biblioteca de variações aprovadas pelo usuário.
- Shadow mode para medir qualidade sem criar pendência.
- Relatório semanal de bloqueios e reescritas para aprender padrões.
- Testes de conversas longas com múltiplas rodadas e mudanças de escopo.
- Simulador de cliente exigente, confuso, apressado, técnico e indeciso.
