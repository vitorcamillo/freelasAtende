# Estratégia de Testes da IA (Ataque do Bem)

## Objetivo
- Garantir que a IA **não invente fatos**, **não viole regras de segurança** e **nunca envie proposta sem aprovação humana**. A exceção operacional permitida é a abordagem inicial curta em `REGULAR`/`LIVRE`, somente quando `real.write.enabled=true`.
- Validar memória de contexto para manter decisões consistentes ao longo do tempo.

## Suítes críticas
- `IaSafetyRedTeamTest`: bloqueio de conteúdo proibido/alucinatório.
- `AvaliadorIaServiceImplTest`: geração de proposta/resposta com sanitização.
- `AvaliadorIaMemoriaContextoTest`: persistência, recuperação e otimização da memória.
- `AutoAtendimentoServiceImplTest`: proposta sempre pendente com aprovação por botões.

## Gate de cobertura
- JaCoCo agora roda em `verify` e valida classes críticas:
  - `AutoAtendimentoServiceImpl`
  - `AvaliadorIaServiceImpl`
  - `IaSafetyPolicy`

## Comandos úteis
- Rodar testes críticos:
  - `mvn -q -Dtest=IaSafetyRedTeamTest,AvaliadorIaServiceImplTest,AvaliadorIaMemoriaContextoTest,AutoAtendimentoServiceImplTest test`
- Rodar suíte completa + cobertura:
  - `mvn verify`
- Rodar modo alvo 100% (mais rígido):
  - `mvn verify -Pcoverage-100`
- Rodar red team **real** com a IA configurada (sem mock):
  - `mvn -Preal-ia-tests failsafe:integration-test failsafe:verify`
  - Requer `IA_API_KEY` (ou `ANTHROPIC_AUTH_TOKEN`) configurada.
- Rodar red team **extremo** (matriz adversarial ampla):
  - `mvn -Preal-ia-tests -Dredteam.extreme=true failsafe:integration-test failsafe:verify`
- Rodar suíte **100% real sem mocks** (somente IT/RealIT):
  - `mvn -Preal-full-tests failsafe:integration-test failsafe:verify`
- Rodar escrita real (pergunta + conversa) com IDs explícitos:
  - `mvn -Preal-full-tests -Dreal.write.enabled=true -Dreal.test.project.id=123456 -Dreal.test.conversa.id=12345678 failsafe:integration-test failsafe:verify`

## Regra de operação
- A IA pode gerar proposta, mas o envio de proposta só ocorre após comando explícito do usuário:
  - Aprovação/Rejeição por botões no Telegram (`✅ Aprovar` / `❌ Recusar`)
- `ENSINAMENTO` trava envio direto e mantém rascunhos para revisão/ensino.
- `REGULAR` e `LIVRE` podem enviar abordagem inicial curta automaticamente quando a escrita real estiver habilitada.
