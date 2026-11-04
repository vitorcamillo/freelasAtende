package com.freelasatende.service.interfaceServico;

import java.util.Map;

/**
 * Interface para serviço de autenticação no 99Freelas.
 */
public interface AutenticacaoService {

    /**
     * Verifica se a sessão atual é válida.
     */
    boolean verificarSessaoValida();

    /**
     * Renova a sessão se necessário usando credenciais do .env.
     */
    void renovarSessaoSeNecessario();

    /**
     * Realiza login com email e senha.
     */
    void fazerLogin(String email, String senha);

    /**
     * Carrega cookies exportados manualmente (ex: do navegador) em formato JSON.
     * Espera um objeto simples no formato: {"nome":"valor", ...}.
     */
    void carregarCookiesJson(String jsonCookies);

    /**
     * Salva os cookies da sessão atual.
     */
    void salvarCookies();

    /**
     * Carrega cookies salvos anteriormente.
     */
    boolean carregarCookies();

    /**
     * Retorna os cookies atuais para uso em requisições.
     */
    Map<String, String> obterCookies();

    /**
     * Versão monotônica dos cookies carregados em memória.
     *
     * <p>Consumidores que mantêm cache de sessão podem usar este valor para
     * invalidar resultados quando um login assistido ou atualização manual
     * troca os cookies ativos.</p>
     */
    default long versaoCookies() {
        return 0L;
    }
}
