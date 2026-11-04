package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.IaProvider;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.freelasatende.service.impl.TelegramActionMessageFormatter.escaparTelegram;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.limitar;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.listaOuNenhum;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.valorLong;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.valorObjeto;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.valorTexto;

@Slf4j
final class TelegramOperationalContextHelper {

    private static final Path ARQUIVO_REGRAS_OPERACIONAIS = Path.of("data", "regras-comportamento.md");
    private static final Path ARQUIVO_MANUAL_VOZ = Path.of("data", "manual-voz-freelancer.md");

    private TelegramOperationalContextHelper() {
    }

    static String gerarRespostaOperadorComIa(
        boolean operatorChatIaEnabled,
        IaProvider iaProvider,
        AvaliadorIaService avaliadorIaService,
        ControleOperacionalService controleOperacionalService,
        PollingScheduler pollingScheduler,
        String pergunta
    ) {
        if (!operatorChatIaEnabled || !iaConfiguradaParaChat(iaProvider, avaliadorIaService)) {
            return null;
        }
        try {
            String prompt = """
                Voce e o copiloto operacional do FreelasAtende dentro do Telegram.
                Responda em PT-BR, natural, claro e direto.
                Nunca diga que enviou algo ao cliente.
                Se o usuario pedir envio, aprovacao ou recusa, explique que a decisao segura fica nos botoes da pendencia.
                Use somente o contexto abaixo. Se faltar dado, diga o que falta.
                Formate como chat de IA no Telegram usando Markdown simples:
                - Use titulos curtos com *negrito* quando ajudar.
                - Use listas com "- " para passos ou comparacoes.
                - Use `codigo inline` para comandos, arquivos e nomes tecnicos.
                - Se o usuario pedir codigo, entregue em bloco fenced com linguagem:
                  ```java
                  // exemplo
                  ```
                - Nao use tabelas grandes; prefira listas.
                - Nao use HTML.
                - Para informacao atual/externa que voce nao tem, diga que precisa verificar antes.

                Contexto atual:
                %s

                Pergunta do operador:
                %s
                """.formatted(
                contextoCompactoParaIa(avaliadorIaService, controleOperacionalService, pollingScheduler),
                pergunta
            );
            return limparRespostaOperador(iaProvider.enviar(prompt, 700));
        } catch (Exception e) {
            log.debug("Conversa operacional via IA indisponivel: {}", e.getMessage());
            return null;
        }
    }

    static String respostaLocalOperador(ControleOperacionalService controleOperacionalService, String texto) {
        List<AcaoControleDTO> pendencias = listarPendenciasSeguro(controleOperacionalService);
        EnsinoFocoDTO foco = obterFocoSeguro(controleOperacionalService);
        return "Conversa operacional\n\n"
            + "Recebi: " + escaparTelegram(limitar(texto, 220)) + "\n\n"
            + "Contexto atual: "
            + pendencias.size() + " pendência(s), foco "
            + (foco != null && foco.isAtivo() ? "#" + valorLong(foco.getAcaoId()) : "SEM_FOCO")
            + ".\n"
            + "Posso conversar sobre regras, dúvidas, memória, pendências e projeto em foco.\n"
            + "Para ensinar, reescrever, aprovar ou recusar, use os botões da pendência.";
    }

    static boolean acaoTemDuvidaAberta(AcaoControleDTO acao) {
        return textoPreenchido(acao.getPerguntaParaUsuario())
            || listaPreenchida(acao.getDuvidasInternas())
            || listaPreenchida(acao.getInformacoesPendentes())
            || listaPreenchida(acao.getRiscosDecisao())
            || (acao.isConversaEnsinoObrigatoria() && !acao.isConversaEnsinoRespondida());
    }

    static List<AcaoControleDTO> listarPendenciasSeguro(ControleOperacionalService controleOperacionalService) {
        try {
            List<AcaoControleDTO> pendencias = controleOperacionalService.listarAcoesPendentes();
            return pendencias != null ? pendencias : List.of();
        } catch (Exception e) {
            log.debug("Falha ao listar pendencias para Telegram: {}", e.getMessage());
            return List.of();
        }
    }

    static EnsinoFocoDTO obterFocoSeguro(ControleOperacionalService controleOperacionalService) {
        try {
            return controleOperacionalService.obterFocoEnsino();
        } catch (Exception e) {
            log.debug("Falha ao obter foco de ensino para Telegram: {}", e.getMessage());
            return null;
        }
    }

    static Map<String, Object> diagnosticoMemoriaSeguro(AvaliadorIaService avaliadorIaService) {
        try {
            Map<String, Object> diagnostico = avaliadorIaService.obterDiagnosticoMemoriaIa();
            return diagnostico != null ? diagnostico : Map.of();
        } catch (Exception e) {
            log.debug("Falha ao obter diagnostico de memoria IA: {}", e.getMessage());
            return Map.of("saude", "INDISPONIVEL", "erro", e.getMessage());
        }
    }

    static String trechoArquivo(Path arquivo, int limite) {
        try {
            if (Files.exists(arquivo)) {
                String conteudo = Files.readString(arquivo)
                    .replaceAll("\\R{3,}", "\n\n")
                    .trim();
                return limitar(conteudo, limite);
            }
        } catch (Exception e) {
            log.debug("Falha ao ler {}: {}", arquivo, e.getMessage());
        }
        return "-";
    }

    private static boolean iaConfiguradaParaChat(IaProvider iaProvider, AvaliadorIaService avaliadorIaService) {
        Map<String, Object> diagnostico = diagnosticoMemoriaSeguro(avaliadorIaService);
        String provider = iaProvider.getNome() != null ? iaProvider.getNome().toLowerCase(Locale.ROOT) : "";
        boolean providerLocal = provider.contains("ollama") || provider.contains("lmstudio");
        return providerLocal || Boolean.TRUE.equals(diagnostico.get("apiKeyConfigurada"));
    }

    private static String contextoCompactoParaIa(
        AvaliadorIaService avaliadorIaService,
        ControleOperacionalService controleOperacionalService,
        PollingScheduler pollingScheduler
    ) {
        Map<String, Object> memoria = diagnosticoMemoriaSeguro(avaliadorIaService);
        List<AcaoControleDTO> pendencias = listarPendenciasSeguro(controleOperacionalService);
        EnsinoFocoDTO foco = obterFocoSeguro(controleOperacionalService);

        StringBuilder sb = new StringBuilder()
            .append("Modo: ").append(pollingScheduler.getModoAtual()).append("\n")
            .append("Pausado: ").append(pollingScheduler.isPausado()).append("\n")
            .append("Memoria IA: ").append(valorObjeto(memoria.get("saude")))
            .append(", avaliados=").append(valorObjeto(memoria.get("totalProjetosAvaliados")))
            .append(", aprovados=").append(valorObjeto(memoria.get("totalAprovados")))
            .append(", recusados=").append(valorObjeto(memoria.get("totalRecusados")))
            .append("\nRegras aprendidas: ").append(valorObjeto(memoria.get("regrasConsolidadasPreview")))
            .append("\nFoco: ");

        if (foco != null && foco.isAtivo()) {
            sb.append("#").append(valorLong(foco.getAcaoId()))
                .append(" | ").append(valorTexto(foco.getTituloProjeto()));
        } else {
            sb.append("SEM_FOCO");
        }

        sb.append("\nPendencias:\n");
        pendencias.stream().limit(6).forEach(acao -> sb
            .append("- #").append(acao.getId())
            .append(" | ").append(valorTexto(acao.getTipo() != null ? acao.getTipo().name() : null))
            .append(" | ").append(valorTexto(limitar(acao.getTituloProjeto(), 90)))
            .append(" | pergunta_usuario=").append(valorTexto(limitar(acao.getPerguntaParaUsuario(), 160)))
            .append(" | duvidas=").append(listaOuNenhum(acao.getDuvidasInternas()))
            .append("\n"));

        sb.append("\nRegras fixas:\n")
            .append("- Nada enviado ao cliente sem aprovacao humana.\n")
            .append("- Mensagem normal sempre vira conversa com operador.\n")
            .append("- Ensino ou reescrita so ocorre depois do botao da pendencia.\n")
            .append("- Opcoes prontas ficam em botoes; comandos ficam so como legado operacional.\n")
            .append("\nManual de voz:\n")
            .append(trechoArquivo(ARQUIVO_MANUAL_VOZ, 900))
            .append("\n\nDocumento de regras:\n")
            .append(trechoArquivo(ARQUIVO_REGRAS_OPERACIONAIS, 900))
            .append("\n");

        return sb.toString();
    }

    private static String limparRespostaOperador(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.trim();
    }

    private static boolean textoPreenchido(String texto) {
        return texto != null && !texto.isBlank();
    }

    private static boolean listaPreenchida(List<String> itens) {
        return itens != null && itens.stream().anyMatch(item -> item != null && !item.isBlank());
    }
}
