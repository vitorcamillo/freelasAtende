package com.freelasatende.service.interfaceServico;

/**
 * Serviço para extrair e buscar conteúdo de URLs mencionados em descrições de projetos.
 * Permite que a IA tenha mais contexto ao avaliar projetos.
 */
public interface UrlFetcherService {

    /**
     * Extrai URLs de um texto.
     * @param texto Texto contendo URLs
     * @return Lista de URLs encontrados
     */
    java.util.List<String> extrairUrls(String texto);

    /**
     * Busca o conteúdo de URLs relevantes mencionados na descrição do projeto.
     * Apenas URLs de domínios permitidos são fetchaados (99freelas.com.br e relacionados).
     * @param descricao Descrição do projeto contendo URLs
     * @return Texto com o conteúdo dos URLs mais relevantes, ou string vazia se não houver
     */
    String buscarContextoDeUrls(String descricao);
}
