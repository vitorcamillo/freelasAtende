package com.freelasatende.model.enums;

public enum ResultadoValidacaoMensagem {
    PERMITIDO,
    BLOQUEADO_DUPLICADO,
    BLOQUEADO_TOM_SISTEMA,
    BLOQUEADO_SPAM,
    /**
     * Conteudo inseguro (IaSafetyPolicy). Diferente de BLOQUEADO_TOM_SISTEMA,
     * que e julgamento de ESTILO: este e seguranca e nao cede a aprovacao
     * humana. Antes os dois compartilhavam BLOQUEADO_TOM_SISTEMA, o que
     * impedia liberar o julgamento de tom sem afrouxar seguranca junto.
     */
    BLOQUEADO_CONTEUDO_INSEGURO
}
