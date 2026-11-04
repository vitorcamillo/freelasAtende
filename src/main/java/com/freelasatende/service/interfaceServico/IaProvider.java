package com.freelasatende.service.interfaceServico;

/**
 * Interface para provedores de IA.
 * Qualquer implementação (OpenAI, Ollama, LM Studio, etc.)
 * deve seguir este contrato.
 */
public interface IaProvider {

    /**
     * Envia um prompt para a IA e retorna a resposta.
     *
     * @param prompt    texto do prompt
     * @param maxTokens número máximo de tokens na resposta
     * @return resposta textual da IA
     * @throws IaProviderException em caso de falha
     */
    String enviar(String prompt, int maxTokens) throws IaProviderException;

    /**
     * Envia o prompt pulando o modelo informado (que ja respondeu algo
     * inutilizavel, ex.: decisao fora do formato SIM/NAO) e seguindo a cadeia
     * de fallback. Implementacoes sem cadeia propria apenas reenviam.
     *
     * @param prompt         texto do prompt
     * @param maxTokens      numero maximo de tokens na resposta
     * @param modeloAEvitar  modelo que ja falhou e deve ser pulado
     * @return resposta textual da IA
     * @throws IaProviderException em caso de falha
     */
    default String enviarPulandoModelo(String prompt, int maxTokens, String modeloAEvitar)
            throws IaProviderException {
        return enviar(prompt, maxTokens);
    }

    /**
    * @return nome do provider (ex: "OpenAI", "Ollama")
     */
    String getNome();

    /**
     * @return nome do modelo em uso
     */
    String getModelo();

    /**
     * Verifica se o provider está acessível/configurado.
     *
     * @return true se funcionando
     */
    boolean estaDisponivel();

    /**
     * Exceção específica de providers de IA.
     */
    class IaProviderException extends Exception {
        private final int statusCode;
        private final boolean retriable;

        public IaProviderException(String message, int statusCode, boolean retriable) {
            super(message);
            this.statusCode = statusCode;
            this.retriable = retriable;
        }

        public IaProviderException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
            this.retriable = true;
        }

        public int getStatusCode() { return statusCode; }
        public boolean isRetriable() { return retriable; }
    }
}
