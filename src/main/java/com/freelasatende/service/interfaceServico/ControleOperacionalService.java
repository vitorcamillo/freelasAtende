package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.model.dto.ControleOverviewDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.InboxAuditoriaDTO;
import com.freelasatende.model.dto.InboxConversaAuditoriaDTO;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.ModoSistema;

import java.util.List;
import java.util.Optional;

public interface ControleOperacionalService {

    ControleOverviewDTO obterOverview();

    List<AcaoControleDTO> listarAcoesPendentes();

    Optional<AcaoControleDTO> buscarAcao(Long id);

    ControleAcaoResultadoDTO aprovarAcao(Long id, String comentario);

    ControleAcaoResultadoDTO recusarAcao(Long id, String comentario);

    ControleAcaoResultadoDTO reescreverAcao(Long id, String novoTexto, String comentario);

    ControleAcaoResultadoDTO registrarContextoEnsino(Long id, String orientacaoUsuario);

    ControleAcaoResultadoDTO responderDuvidaIa(Long id, String respostaUsuario, String regraConfirmada);

    ControleAcaoResultadoDTO sincronizarInboxParaEnsino();

    ControleAcaoResultadoDTO resetarRodadaOperacional();

    InboxAuditoriaDTO auditarInboxEnsinoReadOnly();

    InboxAuditoriaDTO obterUltimaAuditoriaInbox();

    List<InboxConversaAuditoriaDTO> listarConversasUltimaAuditoriaInbox();

    ControleAcaoResultadoDTO sincronizarProjetosParaEnsino();

    ControleAcaoResultadoDTO reprocessarProjetoIgnorado(Long projetoId, ModoSistema modo);

    List<MensagemClienteRegistro> listarBloqueiosRecentes();

    EnsinoFocoDTO obterFocoEnsino();

    EnsinoFocoDTO definirFocoEnsino(Long acaoId);

    EnsinoFocoDTO limparFocoEnsino();
}
