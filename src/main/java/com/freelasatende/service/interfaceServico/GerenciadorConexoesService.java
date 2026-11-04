package com.freelasatende.service.interfaceServico;

import java.time.LocalDateTime;

/**
 * Interface para gerenciamento de conexões e limites do 99Freelas.
 */
public interface GerenciadorConexoesService {

    /**
     * Verifica se ainda temos conexoes disponiveis.
     */
    boolean temConexoesDisponiveis();

    /**
     * Registra o uso de uma conexao.
     */
    void registrarUsoConexao();

    /**
     * Reserva uma conexao de forma atomica e condicional ANTES do envio:
     * incrementa o contador diario somente se estiver abaixo do limite.
     * Retorna false (recusa de negocio) quando o limite diario foi alcancado.
     */
    boolean reservarConexao();

    /**
     * Devolve uma conexao reservada cujo envio falhou depois da reserva;
     * o decremento nunca deixa o contador negativo e ignora reservas de antes
     * do ultimo reset diario.
     */
    void devolverConexao(LocalDateTime reservadaEm);

    /**
     * Verifica se precisa renovar sessao hoje.
     */
    boolean precisaRenovarSessao();

    /**
     * Marca que renovamos a sessao hoje.
     */
    void marcarSessaoRenovada();

    /**
     * Reseta contadores diarios se mudou o dia.
     */
    void verificarResetDiario();

    /**
     * Obtem info sobre conexoes restantes.
     */
    String getStatusConexoes();
}