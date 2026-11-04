package com.freelasatende.scheduler;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Projetos descobertos por plataforma, processados um de cada plataforma por
 * vez (round-robin). Antes cada marketplace avaliava TODOS os seus projetos
 * antes do proximo comecar; medido em 27/09/2026: 85 projetos do Freelancer.com
 * levaram ~25min de IA e o Freelancer.com.br (ultimo da fila) esperava.
 */
final class FilasMarketplace {

    /** Projetos descobertos de uma plataforma, ainda nao processados. */
    record Fila(PlataformaFreela plataforma, Deque<Projeto> projetos) {

        /** Ignora nulos e marca a plataforma nos projetos que vieram sem ela. */
        static Fila de(PlataformaFreela plataforma, List<Projeto> descobertos) {
            Deque<Projeto> fila = new ArrayDeque<>();
            for (Projeto projeto : descobertos) {
                if (projeto == null) {
                    continue;
                }
                if (projeto.getPlataforma() == null) {
                    projeto.setPlataforma(plataforma);
                }
                fila.addLast(projeto);
            }
            return new Fila(plataforma, fila);
        }
    }

    interface ProcessadorProjeto {
        void processar(Projeto projeto) throws Exception;
    }

    private FilasMarketplace() {
    }

    /**
     * Falha em uma plataforma vai para {@code aoFalhar} e descarta so o
     * restante da fila DELA; as outras seguem.
     */
    static void processarIntercalado(
        List<Fila> filas,
        ProcessadorProjeto processador,
        BiConsumer<PlataformaFreela, Exception> aoFalhar
    ) {
        List<Fila> ativas = new ArrayList<>(filas);
        while (!ativas.isEmpty()) {
            Iterator<Fila> it = ativas.iterator();
            while (it.hasNext()) {
                Fila fila = it.next();
                Projeto projeto = fila.projetos().pollFirst();
                if (projeto == null) {
                    it.remove();
                    continue;
                }
                try {
                    processador.processar(projeto);
                } catch (Exception e) {
                    aoFalhar.accept(fila.plataforma(), e);
                    it.remove();
                }
            }
        }
    }
}
