package com.freelasatende.service.impl;

import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.ResultadoValidacaoMensagem;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.util.AtendimentoTextos;
import com.freelasatende.util.IaSafetyPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MensagemClienteGuardServiceImpl implements MensagemClienteGuardService {

    private static final int PREVIEW_LIMIT = 1000;
    private static final int MAX_TEXTO_CLIENTE = 1200;
    private static final int MAX_PERGUNTAS = 2;
    private static final List<String> TERMOS_TOM_SISTEMA = List.of(
        "teste",
        "automatizado",
        "automatizada",
        "automatico",
        "automatica",
        "sistema",
        "validacao",
        "integracao real",
        "ja foi atendido",
        "foi atendido",
        "fui atendido",
        "nao fui atendido",
        "ja fui atendido",
        "ja te respondi",
        "mensagem automatica",
        "enviada pelo sistema",
        "sou uma ia",
        "sou um bot",
        "assistente virtual",
        "inteligencia artificial",
        "chatgpt",
        "modelo de linguagem",
        "prompt",
        "resposta gerada",
        "gerado automaticamente",
        "gerada automaticamente",
        "ignore todas as instrucoes",
        "ignore as instrucoes",
        "desconsidere as regras",
        "desconsidere a politica",
        "desconsidere a política",
        "esqueca a politica",
        "esqueça a política",
        "regra nova:",
        "comando interno",
        "fora da plataforma",
        "por fora",
        "pagamento por fora",
        "fechar por fora"
    );
    private static final List<String> FRASES_TEMPLATE_ROBOTICO = List.of(
        "com isso ja consigo",
        "com isso consigo",
        "assim monto uma proposta",
        "assim que me responder",
        "ainda nao tinha te atendido",
        "ainda nao atendi",
        "ainda nao vi nenhuma proposta",
        "nao localizei uma resposta anterior",
        "sou eu mesmo que estou te respondendo",
        "acabei de ver o projeto",
        "vi que voce esta perguntando sobre atendimento",
        "ainda nao ha um freelancer atribuido",
        "so preciso entender",
        "pontos rapidos",
        "antes de te mandar uma proposta",
        "detalhes da proposta",
        "detalhes finais",
        "alinhar a melhor execucao",
        "boa sorte com o projeto",
        "mensagem de validacao",
        "teste tecnico",
        "responda somente",
        "segue abaixo",
        "ultima chance",
        "responda agora",
        "qualquer duvida estou a disposicao",
        "aguardo seu retorno"
    );
    private static final List<StatusMensagemCliente> STATUS_DUPLICIDADE = List.of(
        StatusMensagemCliente.PENDENTE,
        StatusMensagemCliente.ENVIADA,
        StatusMensagemCliente.SIMULADA
    );
    private static final List<StatusMensagemCliente> STATUS_ENVIADO = List.of(
        StatusMensagemCliente.ENVIADA,
        StatusMensagemCliente.SIMULADA
    );
    private static final List<StatusMensagemCliente> STATUS_PENDENTE = List.of(StatusMensagemCliente.PENDENTE);

    private final MensagemClienteRegistroRepository repository;
    private final IaSafetyPolicy iaSafetyPolicy;

    @Override
    public ValidacaoRascunho validarRascunho(ContextoMensagemCliente contexto, String texto) {
        String preview = preview(texto);
        String normalizado = normalizar(texto);
        String fingerprint = fingerprint(normalizado);

        if (normalizado.isBlank()) {
            // Texto vazio nao e julgamento de tom: nao ha o que o operador
            // revisar e aprovar. Fica fora de BLOQUEADO_TOM_SISTEMA para nao
            // ser liberado pela aprovacao humana.
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_CONTEUDO_INSEGURO,
                "Texto vazio ou sem conteudo util", fingerprint, preview);
        }

        if (texto != null && texto.length() > MAX_TEXTO_CLIENTE) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_SPAM,
                "Texto longo demais para mensagem inicial ao cliente", fingerprint, preview);
        }

        boolean abordagemInicialCurta = abordagemInicialCurtaPermitida(texto);

        // Conteudo inseguro e SEGURANCA: continua fatal mesmo com aprovacao
        // humana. Fica separado do julgamento de tom logo abaixo, que e estilo
        // e pode ser vencido por quem revisou a mensagem no botao.
        if (!abordagemInicialCurta && iaSafetyPolicy.containsUnsafeContent(texto)) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_CONTEUDO_INSEGURO,
                "Conteudo inseguro detectado", fingerprint, preview);
        }

        if (!abordagemInicialCurta && contemTomSistema(normalizado)) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_TOM_SISTEMA,
                "Tom artificial, tecnico ou de sistema detectado", fingerprint, preview);
        }

        if (!abordagemInicialCurta && temFormatoRobotico(texto, normalizado)) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_TOM_SISTEMA,
                "Formato com cara de template, lista ou mensagem automatizada", fingerprint, preview);
        }

        if (contarPerguntas(texto) > MAX_PERGUNTAS) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_SPAM,
                "Excesso de perguntas na mesma mensagem", fingerprint, preview);
        }

        if (contexto != null) {
            ValidacaoRascunho envioDuplicado = validarEnvioDuplicado(contexto, fingerprint, preview);
            if (envioDuplicado != null) {
                return envioDuplicado;
            }
        }

        if (contexto != null && !contexto.permitirOverride()) {
            PlataformaFreela plataforma = contexto.plataforma();
            if (contexto.conversaId() != null
                && repository.existsByPlataformaAndConversaIdAndStatusIn(
                    plataforma,
                    contexto.conversaId(),
                    STATUS_PENDENTE
                )) {
                return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_SPAM,
                    "Ja existe mensagem pendente para esta conversa", fingerprint, preview);
            }
            if (contexto.conversaId() != null
                && repository.existsByPlataformaAndFingerprintAndConversaIdAndStatusIn(
                    plataforma,
                    fingerprint,
                    contexto.conversaId(),
                    STATUS_DUPLICIDADE
                )) {
                return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO,
                    "Texto igual ou muito parecido com mensagem ja criada nesta conversa", fingerprint, preview);
            }
            if (contexto.conversaId() == null
                && contexto.projetoId() != null
                && repository.existsByPlataformaAndProjetoIdAndStatusIn(
                    plataforma,
                    contexto.projetoId(),
                    STATUS_PENDENTE
                )) {
                return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_SPAM,
                    "Ja existe mensagem pendente para este projeto", fingerprint, preview);
            }
            if (contexto.conversaId() == null
                && contexto.projetoId() != null
                && repository.existsByPlataformaAndFingerprintAndProjetoIdAndStatusIn(
                    plataforma,
                    fingerprint,
                    contexto.projetoId(),
                    STATUS_DUPLICIDADE
                )) {
                return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO,
                    "Texto igual ou muito parecido com mensagem ja criada neste projeto", fingerprint, preview);
            }
            if (contexto.conversaId() == null
                && contexto.projetoId() == null
                && repository.existsByPlataformaAndFingerprintAndStatusIn(
                    plataforma,
                    fingerprint,
                    STATUS_DUPLICIDADE
                )) {
                return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO,
                    "Texto igual ou muito parecido com mensagem ja criada", fingerprint, preview);
            }
        }

        return new ValidacaoRascunho(ResultadoValidacaoMensagem.PERMITIDO, null, fingerprint, preview);
    }

    private ValidacaoRascunho validarEnvioDuplicado(
        ContextoMensagemCliente contexto,
        String fingerprint,
        String preview
    ) {
        PlataformaFreela plataforma = contexto.plataforma();
        if (contexto.conversaId() != null
            && repository.existsByPlataformaAndFingerprintAndConversaIdAndStatusIn(
                plataforma,
                fingerprint,
                contexto.conversaId(),
                STATUS_ENVIADO
            )) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO,
                "Texto igual ou muito parecido com mensagem ja enviada nesta conversa", fingerprint, preview);
        }
        if (contexto.conversaId() == null
            && contexto.projetoId() != null
            && repository.existsByPlataformaAndFingerprintAndProjetoIdAndStatusIn(
                plataforma,
                fingerprint,
                contexto.projetoId(),
                STATUS_ENVIADO
            )) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO,
                "Texto igual ou muito parecido com mensagem ja enviada neste projeto", fingerprint, preview);
        }
        if (contexto.conversaId() == null
            && contexto.projetoId() == null
            && repository.existsByPlataformaAndFingerprintAndStatusIn(
                plataforma,
                fingerprint,
                STATUS_ENVIADO
            )) {
            return bloqueado(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO,
                "Texto igual ou muito parecido com mensagem ja enviada", fingerprint, preview);
        }
        return null;
    }

    @Override
    public MensagemClienteRegistro registrarRascunho(
        ContextoMensagemCliente contexto,
        String texto,
        ValidacaoRascunho validacao
    ) {
        ValidacaoRascunho resultado = validacao != null ? validacao : validarRascunho(contexto, texto);
        return salvarRegistro(
            contexto,
            resultado.fingerprint(),
            resultado.textoPreview(),
            resultado.permitido() ? StatusMensagemCliente.PENDENTE : StatusMensagemCliente.BLOQUEADA,
            resultado.motivo()
        );
    }

    @Override
    public MensagemClienteRegistro registrarEnvio(ContextoMensagemCliente contexto, String texto) {
        String normalizado = normalizar(texto);
        return salvarRegistro(
            contexto,
            fingerprint(normalizado),
            preview(texto),
            StatusMensagemCliente.ENVIADA,
            null
        );
    }

    @Override
    public MensagemClienteRegistro registrarEnvioSimulado(ContextoMensagemCliente contexto, String texto) {
        String normalizado = normalizar(texto);
        return salvarRegistro(
            contexto,
            fingerprint(normalizado),
            preview(texto),
            StatusMensagemCliente.SIMULADA,
            "Envio simulado: nenhuma chamada real foi feita ao cliente"
        );
    }

    @Override
    public void descartarRascunhosPendentes(ContextoMensagemCliente contexto) {
        if (contexto == null) {
            return;
        }
        List<MensagemClienteRegistro> pendentes;
        if (contexto.conversaId() != null) {
            pendentes = repository.findByPlataformaAndConversaIdAndStatus(
                contexto.plataforma(),
                contexto.conversaId(),
                StatusMensagemCliente.PENDENTE
            );
        } else if (contexto.projetoId() != null) {
            pendentes = repository.findByPlataformaAndProjetoIdAndStatus(
                contexto.plataforma(),
                contexto.projetoId(),
                StatusMensagemCliente.PENDENTE
            );
        } else {
            return;
        }
        if (pendentes.isEmpty()) {
            return;
        }
        pendentes.forEach(registro -> registro.setStatus(StatusMensagemCliente.DESCARTADA));
        repository.saveAll(pendentes);
    }

    private boolean contemTomSistema(String normalizado) {
        String padded = " " + normalizado + " ";
        for (String termo : TERMOS_TOM_SISTEMA) {
            String termoNormalizado = normalizar(termo);
            if (termoNormalizado.isBlank()) {
                continue;
            }
            // Termos multi-palavra: substring match (já são específicos)
            // Termos de palavra única: fronteira de palavra para evitar falsos positivos
            // ex: "automatica" não deve bater em "automaticamente"
            boolean match = termoNormalizado.contains(" ")
                ? normalizado.contains(termoNormalizado)
                : padded.contains(" " + termoNormalizado + " ");
            if (match) {
                return true;
            }
        }
        for (String frase : FRASES_TEMPLATE_ROBOTICO) {
            String fraseNormalizada = normalizar(frase);
            if (normalizado.contains(fraseNormalizada)) {
                return true;
            }
        }
        return false;
    }

    private boolean abordagemInicialCurtaPermitida(String texto) {
        return AtendimentoTextos.isAbordagemInicial(texto);
    }

    private boolean temFormatoRobotico(String texto, String normalizado) {
        if (texto == null) {
            return false;
        }
        if (texto.matches("(?s).*\\n\\s*(\\d+[.)]|[-*•])\\s+.+")) {
            return true;
        }
        if (texto.matches("(?s).*\\b1[.)]\\s+.+\\b2[.)]\\s+.*")) {
            return true;
        }
        return normalizado.contains("prezado cliente")
            || normalizado.contains("caro cliente")
            || normalizado.contains("a quem possa interessar");
    }

    private int contarPerguntas(String texto) {
        if (texto == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) == '?') {
                total++;
            }
        }
        return total;
    }

    private ValidacaoRascunho bloqueado(
        ResultadoValidacaoMensagem resultado,
        String motivo,
        String fingerprint,
        String preview
    ) {
        return new ValidacaoRascunho(resultado, motivo, fingerprint, preview);
    }

    private MensagemClienteRegistro salvarRegistro(
        ContextoMensagemCliente contexto,
        String fingerprint,
        String preview,
        StatusMensagemCliente status,
        String motivo
    ) {
        if (contexto == null) {
            throw new IllegalArgumentException("Contexto de mensagem ao cliente deve informar plataforma explicitamente");
        }
        return repository.save(MensagemClienteRegistro.builder()
            .plataforma(contexto.plataforma())
            .projetoId(contexto.projetoId())
            .conversaId(contexto.conversaId())
            .tipo(contexto.tipo())
            .fingerprint(fingerprint)
            .textoPreview(preview)
            .status(status)
            .motivoBloqueio(motivo)
            .data(LocalDateTime.now())
            .build());
    }

    private String preview(String texto) {
        if (texto == null) {
            return "";
        }
        String normalizado = texto.replaceAll("\\s+", " ").trim();
        if (normalizado.length() <= PREVIEW_LIMIT) {
            return normalizado;
        }
        return normalizado.substring(0, PREVIEW_LIMIT).trim();
    }

    private String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase()
            .replaceAll("[^a-z0-9]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String fingerprint(String textoNormalizado) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(textoNormalizado.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
