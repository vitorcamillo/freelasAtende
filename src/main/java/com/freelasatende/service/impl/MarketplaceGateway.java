package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceGateway {

    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;

    public List<Projeto> buscarProjetosNovos() {
        List<Projeto> projetos = new ArrayList<>();
        for (MarketplaceAdapter adapter : marketplaceAdapterRegistry.ativos()) {
            if (!adapter.capabilities().discovery()) {
                continue;
            }
            try {
                List<Projeto> itens = adapter.buscarProjetosNovos();
                itens.forEach(projeto -> projeto.setPlataforma(adapter.plataforma()));
                projetos.addAll(itens);
            } catch (Exception e) {
                log.warn("[{}] Falha ao buscar projetos: {}", adapter.plataforma().key(), e.getMessage());
            }
        }
        return projetos;
    }

    public List<ConversaResumo> listarConversasInbox() {
        List<ConversaResumo> conversas = new ArrayList<>();
        for (MarketplaceAdapter adapter : marketplaceAdapterRegistry.ativos()) {
            if (!adapter.capabilities().inbox()) {
                continue;
            }
            try {
                List<ConversaResumo> itens = adapter.listarConversasInbox();
                itens.forEach(conversa -> conversa.setPlataforma(adapter.plataforma()));
                conversas.addAll(itens);
            } catch (Exception e) {
                log.warn("[{}][INBOX] Falha ao listar conversas: {}", adapter.plataforma().key(), e.getMessage());
            }
        }
        return conversas;
    }

    public Optional<ConversaResumo> buscarConversaInbox(Long conversaId) {
        if (conversaId == null) {
            return Optional.empty();
        }
        return listarConversasInbox().stream()
            .filter(conversa -> conversaId.equals(conversa.getIdConversa()))
            .findFirst();
    }

    public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        return listarMensagensConversa(conversaId, false);
    }

    public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
        if (conversaId == null) {
            return List.of();
        }

        Optional<ConversaResumo> conversa = buscarConversaInbox(conversaId);
        if (conversa.isPresent()) {
            MarketplaceAdapter adapter = marketplaceAdapterRegistry.para(conversa.get().getPlataforma());
            return adapter.listarMensagensConversa(conversaId, marcarComoVisualizada);
        }

        for (MarketplaceAdapter adapter : marketplaceAdapterRegistry.ativos()) {
            if (!adapter.capabilities().inbox()) {
                continue;
            }
            try {
                List<MensagemConversa> mensagens = adapter.listarMensagensConversa(conversaId, marcarComoVisualizada);
                mensagens.forEach(mensagem -> mensagem.setPlataforma(adapter.plataforma()));
                if (!mensagens.isEmpty()) {
                    return mensagens;
                }
            } catch (Exception e) {
                log.debug("[{}][INBOX] Conversa {} nao resolvida por fallback: {}",
                    adapter.plataforma().key(), conversaId, e.getMessage());
            }
        }
        return List.of();
    }

    public Map<String, Boolean> verificarLogins() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (MarketplaceAdapter adapter : marketplaceAdapterRegistry.ativos()) {
            try {
                status.put(adapter.plataforma().key(), adapter.verificarLogin());
            } catch (Exception e) {
                status.put(adapter.plataforma().key(), false);
            }
        }
        return status;
    }

    public boolean verificarLoginPadrao() {
        try {
            return marketplaceAdapterRegistry.para(PlataformaFreela.FREELAS_99).verificarLogin();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean algumLoginValido() {
        return verificarLogins().values().stream().anyMatch(Boolean.TRUE::equals);
    }
}
