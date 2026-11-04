package com.freelasatende.scheduler;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import com.freelasatende.service.impl.MarketplaceAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ciclo proprio para leitura de conversas.
 *
 * A inbox tinha agenda acoplada ao discovery, que percorre centenas de projetos e
 * paga dezenas de segundos por avaliacao da IA em cada um. Na pratica o cliente
 * esperava mais de uma hora por uma resposta porque a leitura da conversa so
 * acontecia depois que todos os marketplaces terminassem. Aqui a conversa deixa de
 * depender do ritmo do discovery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxScheduler {

    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final PollingScheduler pollingScheduler;

    @Value("${scheduling.enabled:true}")
    private boolean schedulingEnabled;

    private final AtomicBoolean emAndamento = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${sistema.inbox-intervalo:60}000")
    public void sincronizarInboxes() {
        if (!schedulingEnabled || !pollingScheduler.podeSincronizarInbox()) {
            return;
        }
        if (!emAndamento.compareAndSet(false, true)) {
            log.debug("Sincronizacao de inbox anterior ainda em andamento, pulando ciclo.");
            return;
        }
        try {
            ModoSistema modoCiclo = pollingScheduler.modoCicloAtual();
            for (MarketplaceAdapter adapter : marketplaceAdapterRegistry.ativos()) {
                PlataformaFreela plataforma = adapter.plataforma();
                MarketplaceCapabilities capabilities = adapter.capabilities();
                if (plataforma == null || capabilities == null || !capabilities.inbox()) {
                    continue;
                }
                if (pollingScheduler.sessaoEmCooldown(plataforma)) {
                    continue;
                }
                pollingScheduler.sincronizarInboxDaPlataforma(plataforma, modoCiclo);
            }
        } catch (Exception e) {
            log.error("Erro no ciclo de inbox: {}", e.getMessage(), e);
        } finally {
            emAndamento.set(false);
        }
    }
}
