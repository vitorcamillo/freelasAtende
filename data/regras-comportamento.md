# Regras de Ouro do FreelasAtende

## 1. NUNCA Enviar Mensagens por Conta Própria

### ❌ PROIBIDO
- Enviar mensagens para o Telegram sem autorização explícita
- Fazer perguntas ao cliente sem necessidade
- Enviar mensagens "boas vindas" ou "automáticas"
- Mandar mensagens de "encerramento" sem motivo
- Qualquer mensagem que não seja resposta direta a uma ação do sistema

### ✅ PERMITIDO
- Notificações de novos projetos (quando o sistema encontra algo)
- Respostas a comandos do usuário (/start, /status, /feedback)
- Mensagens de erro apenas quando crítico
- Perguntas de aprovação quando no modo REGULAR

## 2. Protocolo de Comunicação

```
SISTEMA DETECTA PROJETO
    ↓
AVALIA COM IA
    ↓
SE CAPAZ → Notifica usuário (MODO)
    ↓
AGUARDA RESPOSTA DO USUÁRIO
    ↓
SÓ ENTÃO age (envia pergunta/proposta)
```

## 3. Regras de Contexto

### Mantém Histórico
- Todas as interações são salvas em `data/historico.json`
- Memória de projetos já avaliados
- Decisões anteriores do usuário

### Aprendizado
- Se usuário recusou projeto similar → não notificar novamente
- Se usuário aprovou com condições → registrar
- Regras aprendidas são salvas em `data/regras-aprendidas.json`

### ⚠️ REGRAS ANTI-HALUCINAÇÃO (CRÍTICAS)

| ID | Regra | Por Que |
|----|-------|---------|
| AH-01 | Respostas IA devem ser EXATAMENTE "SIM" ou "NAO" | Respostas como "SIM MAS..." ou "SIM\n\n" indicam confusão |
| AH-02 | Timeout de IA NÃO significa rejeição | Sistema pode alucinar que não sabehandle |
| AH-03 | Arquivo de memória corrompido deve ser BACKUPADO | Não perder histórico por corrupção |
| AH-04 | Modo deve ser capturado no INÍCIO do batch | Mudança de modo não pode afetar projetos já em processamento |
| AH-05 | Concorrência requer LOCK | Arquivo memoria.json não pode ser lido/escrito simultaneamente |
| AH-06 | Respostas IA ambíguas exigem LOG e flag | Sistema não pode "adivinhar" intenções da IA |
| AH-07 | Division by zero deve ser tratados | NaN em JSON corrompe memória |

## 4. Limites de Mensagens

| Situação | Limite |
|----------|--------|
| Projetos por ciclo | Máximo 3 por verificação |
| Mensagens por projeto | Máximo 2 (dúvidas + aprovação) |
| Recusas seguidas | Pausar após 5 recusas consecutivas |
| Sessão | Resetar contadores às 6h |

### ⚠️ LIMITE IMPOSTO POR CÓDIGO (NÃO SÓ DOCUMENTAÇÃO)

```java
private static final int MAX_PROJETOS_POR_CICLO = 3;
private static final int MAX_MENSAGENS_POR_PROJETO = 2;
```

## 5. Flags de Segurança

```java
private boolean podeEnviarMensagem = false;  // Só true após ação do sistema
private boolean emAvaliacao = false;        // Em processo de avaliação
private boolean aguardandoResposta = false; // Aguarda input do usuário
```

### ⚠️ Novas Flags de Proteção

```java
private boolean iaDisponivel = true;        // Flag para distinguir timeout de erro
private boolean memoriaValida = true;        // Flag para detectar corrupção
private ReentrantLock lockMemoria = new ReentrantLock(); // Previne escrita concorrente
```

## 6. Quando PAUSAR Envios

- Após 5 projetos recusados consecutivos pelo usuário
- Quando limite de conexões atingir 80%
- Quando sessão não foi renovada há mais de 24h
- Quando IA retorna erro 3 vezes seguidas
- Quando cliente pede explicitamente "parar"
- **NOVA: Quando taxa de sucesso calculada for NaN ou Infinity**
- **NOVA: Quando arquivo de memória estiver corrompido**
- **NOVA: Quando IA retornar resposta ambígua (não é SIM nem NAO puro)**

## 7. Mensagens Permitidas por Tipo

### ✅ Notificação de Projeto
```
🎯 Projeto interessante

[Título]
Valor: R$ XXX
Propostas: X
```

### ✅ Pergunta de Aprovação
```
💰 Proposta sugerida

Valor: R$ XXX
Aprovar? (responda /sim ou /nao)
```

### ✅ Pergunta de Dúvidas
```
❓ Dúvidas sobre projeto

[Lista de dúvidas]
```

### ❌ NUNCA Enviar
- "Olá! Como posso ajudar?"
- "Tudo bem com você?"
- "Sou um bot que帮助你"
- "Obrigado por usar nosso serviço"
- Mensagens de marketing ou propaganda

## 8. Cenários de Alucinação Detectados

### 🔴 CRÍTICO - Corrupção de Memória Concorrente
```
Thread A: read() → modify() → write()
Thread B: read() → modify() → write()  (INTERFERE!)

PREVENÇÃO: ReentrantLock em atualizarMemoria()
```

### 🔴 CRÍTICO - Duplicação de Projeto por Race Condition
```
Thread A: projetoJaProcessado(id) → FALSE
Thread B: projetoJaProcessado(id) → FALSE
Thread A: processa projeto
Thread B: processa projeto (DUPLICADO!)

PREVENÇÃO: Transação SERIALIZABLE + marcação imediata
```

### 🔴 CRÍTICO - Modo Muda Durante Batch
```
Ciclo inicia em REGULAR
Usuário muda para LIVRE
Projetos restantes usam LIVRE

PREVENÇÃO: ModoSnapshot = modoAtual.get() no início
```

### 🟡 ALTO - IA Timeout = Rejeição Falsa
```
A IA não responde em 60s
Código retorna "NAO" por default
Projeto legítimo é rejeitado silenciosamente

PREVENÇÃO: Distinguir timeout de resposta
```

### 🟡 ALTO - AI Response Injection
```
IA retorna: "SIM, mas lembre-se de ligar para o cliente"
Código interpreta como SIM
Sistema alucina que cliente pode ser contactado

PREVENÇÃO: Validação ESTRITA - só "SIM" ou "NAO" aceitos
```

### 🟡 ALTO - Aprendizado É fachada
```
usuário rejeita 10 projetos PHP
sistema continua notificando projetos PHP
aprendizado nunca é aplicado

PREVENÇÃO: Implementar corretamente ou remover pretexto
```

## 9. Testes de Stress (HS-*)

| ID | Teste |
|----|-------|
| HS-01 | Memoria corrupta detectada e recuperada |
| HS-02 | Resposta "SIM MAS" rejeitada |
| HS-03 | Resposta "SIM\n\n" rejeitada |
| HS-04 | Timeout tratado como NAO |
| HS-05 | Zero avaliacoes nao causa NaN |
| HS-07 | Atualizacao concorrente de memoria |
| HS-08 | Projeto duplicado nao reprocessado |
| HS-09 | Modo capturado no inicio do batch |
| HS-10 | Limite MAX_PROJETOS_POR_CICLO respeitado |
| HS-13 | Caracteres especiais preservados |
| HS-14 | Tags HTML removidas da resposta |
| HS-15 | AprendizadoService persiste no repository |
| HS-22 | HTTPClient thread-safe |
| HS-23 | Arquivo memoria tem versao valida |
| HS-24 | Modo alterado atomicamente |

## 10. Versionamento

| Versão | Data | Mudanças |
|--------|------|----------|
| 1.0 | 2026-04-16 | Versão inicial |
| 1.1 | 2026-04-16 | Adicionadas regras anti-halucinação (AH-01 a AH-07) |
