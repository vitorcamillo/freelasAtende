package com.freelasatende.service.impl;

import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.util.AtendimentoTextos;
import com.freelasatende.util.IaSafetyPolicy;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class QualidadeRascunhoServiceImpl implements QualidadeRascunhoService {

    // Limites abaixo do corte de seguranca (900 proposta / 700 conversa) para que
    // o sinal de texto longo realmente apareca antes da mensagem ser truncada.
    private static final int LIMITE_IDEAL = 550;
    private static final int LIMITE_MAXIMO = 800;
    private static final int MINIMO_PALAVRAS_FRASE = 5;
    private static final double LIMITE_SIMILARIDADE_FRASE = 0.7;
    /** Palavras sem carga semantica: nao contam como ideia repetida. */
    private static final Set<String> PALAVRAS_NEUTRAS = Set.of(
        "para", "como", "isso", "esse", "essa", "esta", "sobre", "voce",
        "aqui", "mais", "pode", "posso", "tenho", "fazer", "entao", "muito",
        "onde", "quando", "qual", "seus", "suas", "pelo", "pela", "minha",
        "sera", "seja", "tambem", "desse", "dessa", "algum", "alguma"
    );
    private static final List<String> TERMOS_TOM_SISTEMA = List.of(
        "teste",
        "automatizado",
        "automatizada",
        "sistema",
        "validacao",
        "integracao real",
        "foi atendido",
        "fui atendido",
        "nao fui atendido",
        "ja fui atendido",
        "ja te respondi",
        "mensagem automatica",
        "sou uma ia",
        "sou um bot",
        "assistente virtual",
        "inteligencia artificial",
        "chatgpt",
        "modelo de linguagem",
        "prompt",
        "resposta gerada",
        "ignore todas as instrucoes",
        "ignore as instrucoes",
        "desconsidere as regras",
        "desconsidere a politica",
        "desconsidere a política",
        "esqueca a politica",
        "esqueça a política",
        "regra nova:",
        "comando interno"
    );
    private static final List<String> FRASES_TEMPLATE = List.of(
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
        "detalhes da proposta",
        "detalhes finais",
        "alinhar a melhor execucao",
        "vou revisar esse ponto",
        "sigo por aqui com o proximo passo",
        "sigo por aqui com o próximo passo",
        "te atualizo por aqui com o proximo passo",
        "te atualizo por aqui com o próximo passo",
        "boa sorte com o projeto",
        "segue abaixo",
        "prezado cliente",
        "caro cliente",
        "a quem possa interessar",
        "qualquer duvida estou a disposicao",
        "aguardo seu retorno"
    );
    private static final List<String> URGENCIA_ARTIFICIAL = List.of(
        "so hoje",
        "ultima chance",
        "responda agora",
        "nao perca",
        "garanto 100",
        "100% garantido"
    );
    private static final List<String> PROMESSAS_PRECO_SEM_VALIDACAO = List.of(
        "novo valor",
        "repassar um novo valor",
        "reducao",
        "desconto",
        "baixar o valor",
        "melhorar o preco",
        "preco melhor",
        "valor melhor",
        "atualizo a proposta",
        "atualizar a proposta",
        "consigo fazer por",
        "faco por",
        "fecho por"
    );
    private static final List<String> VALIDADORES_PRECO = List.of(
        "depende do escopo",
        "validar o escopo",
        "validar escopo",
        "avaliar o escopo",
        "avaliar escopo",
        "ajustar o escopo",
        "ajustar escopo",
        "entender o escopo",
        "entender escopo",
        "valor que esta mais confortavel",
        "orcamento",
        "antes de confirmar valor",
        "sem fechar valor"
    );
    private static final List<String> PROMESSAS_PRAZO_SEM_VALIDACAO = List.of(
        "entrego hoje",
        "entrego ate",
        "entrego até",
        "te entrego hoje",
        "te entrego ate",
        "fica pronto hoje",
        "fica pronto ate",
        "fica pronto até",
        "garanto entrega",
        "garanto o prazo",
        "consigo entregar hoje",
        "consigo entregar ate",
        "consigo entregar até"
    );
    private static final List<String> VALIDADORES_PRAZO = List.of(
        "depende do escopo",
        "confirmar escopo",
        "validar escopo",
        "validar prioridade",
        "alinhar prioridade",
        "estimar depois",
        "antes de confirmar prazo",
        "prazo realista depois"
    );

    @Override
    public QualidadeRascunhoDTO avaliar(String texto) {
        return avaliar(texto, null, null);
    }

    @Override
    public QualidadeRascunhoDTO avaliar(String texto, String statusGuardiao, String motivoGuardiao) {
        String normalizado = normalizar(texto);
        if (abordagemInicialCurtaPermitida(texto)) {
            return QualidadeRascunhoDTO.builder()
                .scoreNaturalidade(95)
                .scoreClareza(90)
                .scoreRisco(0)
                .scoreDuplicidade(0)
                .scoreConfianca(94)
                .nivelConfianca("ALTA")
                .decisaoRecomendada("APROVAR")
                .comentario("Abordagem inicial curta aprovada para projeto frio.")
                .sinais(List.of("abordagem_inicial_curta"))
                .build();
        }
        List<String> sinais = new ArrayList<>();
        int naturalidade = 100;
        int clareza = 100;
        int risco = 0;
        int duplicidade = 0;

        if (normalizado.isBlank()) {
            sinais.add("texto_vazio");
            naturalidade = 0;
            clareza = 0;
            risco = 80;
        }

        if (texto != null && texto.length() > LIMITE_IDEAL) {
            sinais.add("texto_longo");
            naturalidade -= 15;
            clareza -= 15;
            risco += 10;
        }
        if (texto != null && texto.length() > LIMITE_MAXIMO) {
            sinais.add("texto_longo_demais");
            naturalidade -= 30;
            clareza -= 25;
            risco += 35;
        }
        if (texto != null && texto.trim().length() > 0 && texto.trim().length() < 20) {
            sinais.add("texto_curto_demais");
            clareza -= 30;
        }

        if (IaSafetyPolicy.containsUnsafeContent(texto) || normalizado.contains("fora da plataforma")) {
            sinais.add("risco_regra_plataforma");
            naturalidade -= 20;
            risco += 75;
        }

        if (contem(normalizado, TERMOS_TOM_SISTEMA)) {
            sinais.add("tom_sistema_ou_ia");
            naturalidade -= 55;
            risco += 75;
        }

        if (contem(normalizado, FRASES_TEMPLATE) || temFormatoLista(texto)) {
            sinais.add("parece_template");
            naturalidade -= 35;
            clareza -= 10;
            risco += 25;
        }

        if (contem(normalizado, URGENCIA_ARTIFICIAL)) {
            sinais.add("urgencia_artificial");
            naturalidade -= 25;
            risco += 35;
        }

        if (promessaSemValidacao(normalizado, PROMESSAS_PRECO_SEM_VALIDACAO, VALIDADORES_PRECO)) {
            sinais.add("promessa_preco_sem_validacao");
            naturalidade -= 25;
            clareza -= 15;
            risco += 75;
        }

        if (promessaSemValidacao(normalizado, PROMESSAS_PRAZO_SEM_VALIDACAO, VALIDADORES_PRAZO)) {
            sinais.add("promessa_prazo_sem_validacao");
            naturalidade -= 25;
            clareza -= 15;
            risco += 75;
        }

        if (temIdeiaRepetida(texto)) {
            sinais.add("ideia_repetida");
            naturalidade -= 30;
            clareza -= 25;
            risco += 30;
        }

        int perguntas = contarPerguntas(texto);
        if (perguntas > 2) {
            sinais.add("perguntas_demais");
            naturalidade -= 20;
            clareza -= 20;
            risco += 15;
        }

        if (texto != null && texto.matches("(?s).*[!]{2,}.*")) {
            sinais.add("pontuacao_exagerada");
            naturalidade -= 10;
        }

        String status = statusGuardiao != null ? statusGuardiao.toUpperCase() : "";
        String motivo = motivoGuardiao != null ? normalizar(motivoGuardiao) : "";
        if (status.contains("BLOQUEADA") || status.contains("BLOQUEADO") || !motivo.isBlank()) {
            sinais.add("guardiao_exige_revisao");
            naturalidade -= 20;
            risco += 40;
        }
        if (status.contains("DUPLICADO") || motivo.contains("duplicado") || motivo.contains("igual ou muito parecido")) {
            sinais.add("duplicidade");
            duplicidade = 100;
            risco += 35;
        }
        if (motivo.contains("pendente para este projeto") || motivo.contains("pendente para esta conversa")) {
            sinais.add("spam_operacional");
            risco += 45;
        }

        naturalidade = clamp(naturalidade);
        clareza = clamp(clareza);
        risco = clamp(risco);
        duplicidade = clamp(duplicidade);

        int confianca = clamp((naturalidade * 35 + clareza * 30 + (100 - risco) * 25 + (100 - duplicidade) * 10) / 100);
        String decisao = decisao(naturalidade, clareza, risco, duplicidade, confianca);
        String nivel = nivelConfianca(decisao, confianca, risco);

        return QualidadeRascunhoDTO.builder()
            .scoreNaturalidade(naturalidade)
            .scoreClareza(clareza)
            .scoreRisco(risco)
            .scoreDuplicidade(duplicidade)
            .scoreConfianca(confianca)
            .nivelConfianca(nivel)
            .decisaoRecomendada(decisao)
            .comentario(comentario(decisao, sinais))
            .sinais(new ArrayList<>(new LinkedHashSet<>(sinais)))
            .build();
    }

    private boolean contem(String normalizado, List<String> termos) {
        String colado = normalizado.replaceAll("[^a-z0-9]", "");
        for (String termo : termos) {
            String termoNormalizado = normalizar(termo);
            String termoColado = termoNormalizado.replaceAll("[^a-z0-9]", "");
            if (normalizado.contains(termoNormalizado) || colado.contains(termoColado)) {
                return true;
            }
        }
        return false;
    }

    private boolean abordagemInicialCurtaPermitida(String texto) {
        return AtendimentoTextos.isAbordagemInicial(texto);
    }

    private boolean promessaSemValidacao(String normalizado, List<String> promessas, List<String> validadores) {
        if (!contem(normalizado, promessas)) {
            return false;
        }
        return !contem(normalizado, validadores);
    }

    private boolean temFormatoLista(String texto) {
        if (texto == null) {
            return false;
        }
        return texto.matches("(?s).*\\n\\s*(\\d+[.)]|[-*•])\\s+.+")
            || texto.matches("(?s).*\\b1[.)]\\s+.+\\b2[.)]\\s+.*");
    }

    /**
     * Detecta duas frases da mesma mensagem que dizem a mesma coisa com outras palavras.
     * Cobre o eco de ideia dentro do proprio texto, que o sinal de duplicidade
     * (vindo do guardiao) nao pega porque ele compara com mensagens anteriores.
     */
    private boolean temIdeiaRepetida(String texto) {
        List<Set<String>> frases = frasesRelevantes(texto);
        for (int i = 0; i < frases.size(); i++) {
            for (int j = i + 1; j < frases.size(); j++) {
                if (similaridade(frases.get(i), frases.get(j)) >= LIMITE_SIMILARIDADE_FRASE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Quebra o texto em frases e devolve apenas o conjunto de palavras com carga
     * semantica de cada uma. Frases curtas sao ignoradas para evitar falso positivo.
     */
    private List<Set<String>> frasesRelevantes(String texto) {
        List<Set<String>> frases = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return frases;
        }
        for (String bruta : texto.split("[.!?\\n]+")) {
            Set<String> palavras = new LinkedHashSet<>();
            for (String palavra : normalizar(bruta).split(" ")) {
                if (palavra.length() > 3 && !PALAVRAS_NEUTRAS.contains(palavra)) {
                    palavras.add(palavra);
                }
            }
            if (palavras.size() >= MINIMO_PALAVRAS_FRASE) {
                frases.add(palavras);
            }
        }
        return frases;
    }

    /** Similaridade de Jaccard entre os conjuntos de palavras de duas frases. */
    private double similaridade(Set<String> a, Set<String> b) {
        Set<String> intersecao = new LinkedHashSet<>(a);
        intersecao.retainAll(b);
        if (intersecao.isEmpty()) {
            return 0;
        }
        Set<String> uniao = new LinkedHashSet<>(a);
        uniao.addAll(b);
        return (double) intersecao.size() / uniao.size();
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

    private String decisao(int naturalidade, int clareza, int risco, int duplicidade, int confianca) {
        if (risco >= 70 || duplicidade >= 80 || naturalidade < 40 || clareza < 35) {
            return "BLOQUEAR";
        }
        if (confianca < 78 || risco >= 30 || naturalidade < 75 || clareza < 75) {
            return "REESCREVER";
        }
        return "APROVAR";
    }

    private String nivelConfianca(String decisao, int confianca, int risco) {
        if ("BLOQUEAR".equals(decisao)) {
            return "BLOQUEADA";
        }
        if (confianca >= 82 && risco < 25) {
            return "ALTA";
        }
        return "MEDIA";
    }

    private String comentario(String decisao, List<String> sinais) {
        if ("APROVAR".equals(decisao)) {
            return "Mensagem natural e clara para revisao humana.";
        }
        if ("BLOQUEAR".equals(decisao)) {
            return "Mensagem deve ser revisada antes de qualquer aprovacao.";
        }
        if (sinais.isEmpty()) {
            return "Mensagem segura, mas vale revisar para melhorar fluidez.";
        }
        return "Revisar: " + String.join(", ", sinais);
    }

    private int clamp(int valor) {
        return Math.max(0, Math.min(100, valor));
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
}
