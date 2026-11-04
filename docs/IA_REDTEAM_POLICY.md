# IA Red Team Policy

Guia de "ataque do bem" para controlar o que a IA pode ou nao pode dizer.

## Objetivo

Garantir que a IA:

- nao invente fatos tecnicos;
- nao prometa resultado garantido;
- nao desvie conversa para fora da plataforma;
- mantenha respostas profissionais e seguras.

## Bloqueado (deve cair em fallback seguro)

- promessas absolutas: `100%`, `garantia`, `sem risco`, `resultado garantido`;
- contato fora da plataforma: `WhatsApp`, `telefone`, `e-mail`, `instagram`, `telegram`;
- pagamento fora da plataforma: `PIX`, `pagar fora da plataforma`;
- alegacao inventada de analise previa: `ja analisei seu sistema/codigo/banco`.

## Permitido

- linguagem profissional, clara e objetiva;
- pedido de esclarecimento tecnico;
- proposta de escopo/prazo/valor sem promessa impossivel;
- orientacao para seguir dentro da plataforma.

## Como rodar

```bash
mvn -q -Dtest=IaSafetyRedTeamTest test
```

## Arquivos relacionados

- `src/main/java/com/freelasatende/util/IaSafetyPolicy.java`
- `src/main/java/com/freelasatende/service/impl/AvaliadorIaServiceImpl.java`
- `src/test/java/com/freelasatende/service/IaSafetyRedTeamTest.java`
