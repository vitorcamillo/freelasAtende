package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.MlLabDtos;

import java.util.List;

public interface MlLabService {

    MlLabDtos.RunOverview criarRun(MlLabDtos.CreateRunRequest request);

    MlLabDtos.ActionResult iniciarRun(Long id);

    MlLabDtos.ActionResult pausarRun(Long id);

    MlLabDtos.ActionResult pararRun(Long id);

    MlLabDtos.RunOverview obterOverview(Long id);

    List<MlLabDtos.RunOverview> listarRuns();

    List<MlLabDtos.AgentView> listarAgentes(Long runId);

    MlLabDtos.ChatResponse conversar(Long runId, String agentKey, MlLabDtos.ChatRequest request);

    MlLabDtos.ActionResult aprovarCandidata(Long candidateId);
}
