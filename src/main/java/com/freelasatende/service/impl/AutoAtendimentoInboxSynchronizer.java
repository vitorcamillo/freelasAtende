package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.freelasatende.service.impl.MarketplaceSessionFailure.isSessaoMarketplaceInvalida;

@Slf4j
@RequiredArgsConstructor
class AutoAtendimentoInboxSynchronizer {

    private static final long COOLDOWN_CONVERSA_INACESSIVEL_MILLIS = TimeUnit.HOURS.toMillis(6);

    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final ConversaProcessor conversaProcessor;
    private final ConcurrentMap<String, Long> conversasInacessiveisAte = new ConcurrentHashMap<>();

    void sincronizar(ModoSistema modo, int limiteConversas, Collection<PlataformaFreela> plataformas) {
        int processadas = 0;
        Set<PlataformaFreela> plataformasPermitidas = plataformas == null
            ? null
            : plataformas.stream().collect(Collectors.toSet());
        for (var adapter : marketplaceAdapterRegistry.ativos()) {
            if (!adapter.capabilities().inbox()) {
                continue;
            }
            if (plataformasPermitidas != null && !plataformasPermitidas.contains(adapter.plataforma())) {
                continue;
            }
            try {
                List<ConversaResumo> conversas = adapter.listarConversasInbox();
                // Em INFO quando ha conversa: sem isso o log so mostrava inbox que
                // falhou, e nao dava para saber se a leitura estava acontecendo.
                if (conversas.isEmpty()) {
                    log.debug("[{}][INBOX] nenhuma conversa nesta rodada", adapter.plataforma().key());
                } else {
                    log.info("[{}][INBOX] {} conversas encontradas para o core avaliar",
                        adapter.plataforma().key(), conversas.size());
                }
                for (ConversaResumo conversa : conversas) {
                    if (limiteConversas > 0 && processadas >= limiteConversas) {
                        return;
                    }
                    if (processar(adapter, conversa, modo)) {
                        processadas++;
                    }
                }
            } catch (Exception e) {
                if (isSessaoMarketplaceInvalida(e)) {
                    throw e;
                }
                if (e instanceof MarketplaceAdapterException marketplaceError
                    && marketplaceError.getType() == MarketplaceAdapterErrorType.FALHA_REDE) {
                    log.warn("Inbox {} indisponivel nesta rodada: {}", adapter.plataforma().key(), e.getMessage());
                } else {
                    log.error("Erro ao sincronizar inbox {}: {}", adapter.plataforma().key(), e.getMessage(), e);
                }
            }
        }
    }

    private boolean processar(
        com.freelasatende.service.interfaceServico.MarketplaceAdapter adapter,
        ConversaResumo conversa,
        ModoSistema modo
    ) {
        try {
            String chaveConversa = chaveConversa(adapter.plataforma(), conversa.getIdConversa());
            if (conversaInacessivelEmCooldown(chaveConversa)) {
                return false;
            }
            conversa.setPlataforma(adapter.plataforma());
            conversaProcessor.processar(conversa, modo);
            return true;
        } catch (MarketplaceAdapterException e) {
            if (isSessaoMarketplaceInvalida(e)) {
                throw e;
            }
            if (e.getType() == MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL) {
                marcarConversaInacessivel(adapter.plataforma(), conversa.getIdConversa());
                log.warn(
                    "Ignorando conversa {} em {}: {}",
                    conversa.getIdConversa(),
                    adapter.plataforma().key(),
                    e.getMessage()
                );
            } else if (e.getType() == MarketplaceAdapterErrorType.FALHA_REDE) {
                log.warn(
                    "Conversa {} em {} indisponivel nesta rodada: {}",
                    conversa.getIdConversa(),
                    adapter.plataforma().key(),
                    e.getMessage()
                );
                return false;
            } else {
                log.error(
                    "Erro ao processar conversa {} em {}: {}",
                    conversa.getIdConversa(),
                    adapter.plataforma().key(),
                    e.getMessage(),
                    e
                );
            }
            return true;
        } catch (Exception e) {
            if (isSessaoMarketplaceInvalida(e)) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.error(
                "Erro ao processar conversa {} em {}: {}",
                conversa.getIdConversa(),
                adapter.plataforma().key(),
                e.getMessage(),
                e
            );
            return true;
        }
    }

    private String chaveConversa(PlataformaFreela plataforma, Long conversaId) {
        return plataforma.key() + ":" + conversaId;
    }

    private boolean conversaInacessivelEmCooldown(String chaveConversa) {
        Long indisponivelAte = conversasInacessiveisAte.get(chaveConversa);
        if (indisponivelAte == null) {
            return false;
        }
        long agora = System.currentTimeMillis();
        if (agora >= indisponivelAte) {
            conversasInacessiveisAte.remove(chaveConversa, indisponivelAte);
            return false;
        }
        return true;
    }

    private void marcarConversaInacessivel(PlataformaFreela plataforma, Long conversaId) {
        conversasInacessiveisAte.put(
            chaveConversa(plataforma, conversaId),
            System.currentTimeMillis() + COOLDOWN_CONVERSA_INACESSIVEL_MILLIS
        );
    }

    @FunctionalInterface
    interface ConversaProcessor {
        void processar(ConversaResumo conversa, ModoSistema modo) throws Exception;
    }
}
