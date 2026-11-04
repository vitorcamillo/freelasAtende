package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.interfaceServico.HistoricoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * Implementacao do servico de historico.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricoServiceImpl implements HistoricoService {

    private static final int LIMITE_CONTEUDO_INTERACAO = 5000;
    private static final EnumSet<TipoInteracao> TIPOS_NAO_CONCLUSIVOS = EnumSet.of(
        TipoInteracao.ERRO_SISTEMA,
        TipoInteracao.MENSAGEM_ERRO
    );

    private final InteracaoRepository interacaoRepository;

    @Override
    public void registrarInteracao(Interacao interacao) {
        interacao.setPlataforma(PlataformaFreela.normalizar(interacao.getPlataforma()));
        interacao.setData(LocalDateTime.now());
        interacao.setConteudo(limitarConteudo(interacao.getConteudo()));
        interacaoRepository.save(interacao);
        log.debug("Interacao registrada: {} - {}", interacao.getTipo(), interacao.getProjetoTitulo());
    }

    @Override
    public List<Interacao> buscarPorProjeto(Long projetoId) {
        return interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
    }

    @Override
    public List<Interacao> buscarPorProjeto(PlataformaFreela plataforma, Long projetoId) {
        return interacaoRepository.findByPlataformaAndProjetoIdOrderByDataDesc(
            PlataformaFreela.normalizar(plataforma),
            projetoId
        );
    }

    @Override
    public List<Interacao> buscarUltimas(int quantidade) {
        if (quantidade <= 0 || quantidade > 1000) {
            quantidade = 100;
        }
        return interacaoRepository.findAllByOrderByDataDesc(PageRequest.of(0, quantidade));
    }

    @Override
    public List<Interacao> buscarPorTipo(TipoInteracao tipo) {
        return interacaoRepository.findByTipoOrderByDataDesc(tipo);
    }

    @Override
    public int contarInteracoesDoDia() {
        LocalDateTime inicio = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime fim = LocalDateTime.now();
        return interacaoRepository.countByDataBetween(inicio, fim);
    }

    @Override
    public boolean projetoJaProcessado(Long projetoId) {
        return interacaoRepository.existsByProjetoIdAndTipoNotIn(projetoId, TIPOS_NAO_CONCLUSIVOS);
    }

    @Override
    public boolean projetoJaProcessado(PlataformaFreela plataforma, Long projetoId) {
        return interacaoRepository.existsByPlataformaAndProjetoIdAndTipoNotIn(
            PlataformaFreela.normalizar(plataforma),
            projetoId,
            TIPOS_NAO_CONCLUSIVOS
        );
    }

    @Override
    public List<Interacao> obterInteracoesRecentes() {
        return buscarUltimas(100);
    }

    public Interacao criarInteracao(Long projetoId, String titulo, TipoInteracao tipo, ModoSistema modo) {
        return Interacao.builder()
            .projetoId(projetoId)
            .projetoTitulo(titulo)
            .tipo(tipo)
            .modoUsado(modo)
            .data(LocalDateTime.now())
            .build();
    }

    private String limitarConteudo(String conteudo) {
        if (conteudo == null || conteudo.length() <= LIMITE_CONTEUDO_INTERACAO) {
            return conteudo;
        }
        return conteudo.substring(0, LIMITE_CONTEUDO_INTERACAO - 40)
            + "\n[conteudo cortado por limite tecnico]";
    }
}
