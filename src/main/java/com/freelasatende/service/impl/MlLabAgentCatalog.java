package com.freelasatende.service.impl;

import java.util.List;

final class MlLabAgentCatalog {

    private MlLabAgentCatalog() {
    }

    static List<MlLabAgentProfile> perfis() {
        return List.of(
            new MlLabAgentProfile("Atendimento seguro/conservador", "reduzir risco, pedir confirmacao e nunca prometer"),
            new MlLabAgentProfile("Vendedor consultivo premium", "aumentar valor percebido sem inflar promessa"),
            new MlLabAgentProfile("Tecnico detalhista", "traduzir escopo tecnico em duvidas verificaveis"),
            new MlLabAgentProfile("Junior perguntador", "perguntar cedo quando falta contexto"),
            new MlLabAgentProfile("Especialista WordPress/sites", "priorizar sites, landing pages e manutencao web"),
            new MlLabAgentProfile("Especialista automacao/IA", "priorizar automacoes, APIs e fluxos com IA"),
            new MlLabAgentProfile("Filtro de lucro e risco", "bloquear projeto ruim e proteger margem"),
            new MlLabAgentProfile("Conversa curta e humana", "responder natural, curto e sem parecer robo"),
            new MlLabAgentProfile("Negociador de prazo/orcamento", "confirmar prazo, escopo e valor antes de compromisso"),
            new MlLabAgentProfile("Diagnostico de legados complexos", "mapear riscos em sistemas antigos e projetos grandes")
        );
    }

    static List<MlLabAgentVariant> variantes() {
        return List.of(
            new MlLabAgentVariant("A", "conservadora", "mais cautela, mais perguntas ao dono"),
            new MlLabAgentVariant("B", "equilibrada", "balanceia conversa, venda e seguranca"),
            new MlLabAgentVariant("C", "exploratoria", "testa caminhos e alternativas com mais criatividade")
        );
    }
}
