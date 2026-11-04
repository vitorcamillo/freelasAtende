# Como contribuir

Contribuições são bem-vindas. Antes de abrir uma issue ou pull request, confira as instruções de execução e os limites de segurança do projeto no [README](README.md).

## Ambiente e testes

Use JDK 17 ou superior e Maven 3.9 ou superior. Para executar a suíte padrão:

```bash
mvn test
```

Para validar também as etapas de verificação configuradas no Maven:

```bash
mvn verify
```

Prefira testes com fixtures e simulações locais. Não use contas reais, sessões autenticadas ou dados de clientes para desenvolver ou validar mudanças. Não execute perfis de testes reais contra serviços externos.

## Issues e pull requests

Abra uma issue para descrever um problema ou propor uma mudança. Inclua, quando aplicável, os passos para reproduzir, o comportamento esperado e o observado, a plataforma afetada e evidências sanitizadas. Não inclua dados pessoais ou conteúdo de conversas.

Em pull requests, explique o motivo e o efeito da mudança, liste os arquivos ou áreas afetadas e informe os comandos de teste executados e seus resultados. Mantenha cada alteração focada e associe a issue relacionada, se houver.

## Segredos e dados

Nunca adicione ao repositório cookies, tokens, senhas, chaves, arquivos `.env`, dados de clientes ou capturas que revelem essas informações. Revise os arquivos staged antes de enviar uma contribuição e remova ou sanitize qualquer dado sensível.

## Vulnerabilidades

Não publique detalhes de vulnerabilidades em issues ou pull requests públicos. Reporte-as por um canal privado disponibilizado pelo mantenedor ou pela plataforma de hospedagem do projeto. Não há um endereço de contato de segurança publicado neste repositório.
