package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.enums.PlataformaFreela;

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
     * Marca que o marketplace da plataforma recusou envio por limite de
     * conexoes no lado remoto (ex.: limite semanal do Workana). Abre o
     * circuito apenas dessa plataforma ate o reset diario local, evitando
     * tentativa por projeto quando a conta esta sem conexoes no marketplace
     * e sem bloquear as demais plataformas.
     */
    void marcarLimiteRemotoExterno(PlataformaFreela plataforma);

    /**
     * Circuito remoto da plataforma esta aberto (recusa por limite de
     * conexoes no lado remoto ainda vigente para ela).
     */
    boolean limiteRemotoAtivo(PlataformaFreela plataforma);

    /**
     * Reseta contadores diarios se mudou o dia.
     */
    void verificarResetDiario();

    /**
     * Obtem info sobre conexoes restantes.
     */
    String getStatusConexoes();
}