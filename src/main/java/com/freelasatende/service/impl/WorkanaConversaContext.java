package com.freelasatende.service.impl;

record WorkanaConversaContext(
    Long conversaId,
    Long projetoId,
    Long projetoIdExterno,
    String slugProjeto,
    String nomeProjeto,
    Long clienteId,
    String nomeCliente,
    Long freelancerId,
    String inboxUrl,
    String postUrl
) {
}
