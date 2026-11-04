package com.freelasatende.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot do estoque de conexoes da conta no 99Freelas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConexoesDisponiveis {

    private Integer total;
    private Integer renovaveis;
    private Integer naoExpiraveis;
}
