package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.FreelancerConfig;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decide se a conta consegue dar lance num projeto do Freelancer.com antes de
 * gastar IA com ele. Tudo aqui foi recusado pela API de bids em producao
 * (27/09/2026: 116 recusas em 7h, todas depois da avaliacao por IA).
 */
@Slf4j
class FreelancerElegibilidadeConta {

    /** Regra da plataforma: conta nao verificada nao da lance em projeto de US$ 2.500 ou mais. */
    static final BigDecimal LIMITE_USD_SEM_VERIFICACAO = new BigDecimal("2500");
    static final long VALIDADE_SKILLS_MS = Duration.ofHours(6).toMillis();
    static final long NOVA_TENTATIVA_SKILLS_MS = Duration.ofMinutes(10).toMillis();
    static final long PAUSA_SEM_LANCES_MS = Duration.ofHours(6).toMillis();

    private final FreelancerConfig config;
    /** Le /users/0.1/self?jobs=true; so chamado quando o cache de skills vence. */
    private final Supplier<JsonNode> perfil;

    private volatile Set<Long> minhasSkills = Set.of();
    private volatile long proximaLeituraSkills;
    private volatile long lancesBloqueadosAte;

    FreelancerElegibilidadeConta(FreelancerConfig config, Supplier<JsonNode> perfil) {
        this.config = config;
        this.perfil = perfil;
    }

    /** Motivo pelo qual a conta NAO consegue dar lance no projeto, ou null se consegue. */
    String motivoRecusa(JsonNode projeto) {
        if (projeto == null || projeto.isMissingNode() || projeto.isNull()) return "projeto vazio";
        String status = projeto.path("status").asText("");
        if (!status.isBlank() && !"active".equalsIgnoreCase(status)) return "inativo";
        JsonNode upgrades = projeto.path("upgrades");
        if (upgrades.path("pf_only").asBoolean(false) && !config.isPreferred()) return "preferencial";
        if (upgrades.path("qualified").asBoolean(false)) return "qualificacao";
        if (upgrades.path("NDA").asBoolean(false) || upgrades.path("non_compete").asBoolean(false)) return "nda";
        BigDecimal maximo = decimal(projeto.path("budget").path("maximum"));
        boolean porHora = "hourly".equalsIgnoreCase(projeto.path("type").asText());
        if (porHora && maximo.compareTo(new BigDecimal("50")) >= 0 && !config.isVerified()) return "verificacao";
        if (!porHora && !config.isVerified()
            && valorEmUsd(maximo, projeto).compareTo(LIMITE_USD_SEM_VERIFICACAO) >= 0) {
            return "verificacao";
        }
        if (projeto.path("is_escrow_project").asBoolean(false) && !config.isEscrowVinculado()) return "escrow";
        if (!temSkillEmComum(projeto)) return "skills";
        return null;
    }

    /** A conta ficou sem lances ou sem saldo minimo: nao adianta avaliar projeto ate passar a pausa. */
    boolean lancesBloqueados() {
        return System.currentTimeMillis() < lancesBloqueadosAte;
    }

    long lancesBloqueadosAte() {
        return lancesBloqueadosAte;
    }

    void registrarRecusaDeLance(String corpo) {
        if (corpo != null && (corpo.contains("BID_LIMIT_EXCEEDED") || corpo.contains("BID_MINIMUM_REQUIREMENT"))) {
            lancesBloqueadosAte = System.currentTimeMillis() + PAUSA_SEM_LANCES_MS;
            log.warn("Freelancer: conta sem lances ou sem saldo minimo; discovery pausado por {}h",
                Duration.ofMillis(PAUSA_SEM_LANCES_MS).toHours());
        }
    }

    private static BigDecimal valorEmUsd(BigDecimal valor, JsonNode projeto) {
        JsonNode taxa = projeto.path("currency").path("exchange_rate");
        if (!taxa.isNumber() || taxa.asDouble() <= 0) {
            return valor;
        }
        return valor.multiply(taxa.decimalValue());
    }

    /**
     * A plataforma exige ao menos uma skill do projeto no perfil. Sem skills
     * no projeto ou sem conseguir ler o perfil, nao filtra (falha aberta).
     */
    private boolean temSkillEmComum(JsonNode projeto) {
        JsonNode jobs = projeto.path("jobs");
        if (!jobs.isArray() || jobs.isEmpty()) {
            return true;
        }
        Set<Long> skills = minhasSkills();
        if (skills.isEmpty()) {
            return true;
        }
        for (JsonNode job : jobs) {
            if (skills.contains(job.path("id").asLong(-1))) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> minhasSkills() {
        long agora = System.currentTimeMillis();
        if (agora < proximaLeituraSkills || config.getOauthToken().isBlank()) {
            return minhasSkills;
        }
        try {
            Set<Long> ids = new HashSet<>();
            for (JsonNode job : perfil.get().path("result").path("jobs")) {
                long id = job.path("id").asLong(-1);
                if (id > 0) ids.add(id);
            }
            minhasSkills = Set.copyOf(ids);
            proximaLeituraSkills = agora + VALIDADE_SKILLS_MS;
            log.info("Freelancer: {} skills do perfil carregadas para o filtro de discovery", ids.size());
        } catch (Exception e) {
            proximaLeituraSkills = agora + NOVA_TENTATIVA_SKILLS_MS;
            log.warn("Freelancer: nao consegui ler as skills do perfil; filtro de skills desligado por ora: {}",
                e.getMessage());
        }
        return minhasSkills;
    }

    private static BigDecimal decimal(JsonNode valor) {
        try {
            return valor.isNumber() ? valor.decimalValue() : new BigDecimal(valor.asText("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
