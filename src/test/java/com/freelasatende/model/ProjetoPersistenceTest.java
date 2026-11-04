package com.freelasatende.model;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.repository.ProjetoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class ProjetoPersistenceTest {

    @Autowired
    private ProjetoRepository projetoRepository;

    @Test
    @DisplayName("Deve persistir subcategoria longa gerada por lista de skills")
    void devePersistirSubcategoriaLonga() {
        String subcategoriaLonga = IntStream.rangeClosed(1, 80)
            .mapToObj(i -> "Skill tecnica operacional " + i)
            .collect(Collectors.joining(", "));

        Projeto projeto = Projeto.builder()
            .id(2003859663883L)
            .titulo("Projeto com muitas skills")
            .descricao("Descricao teste")
            .categoria("Programacao")
            .subcategoria(subcategoriaLonga)
            .status(StatusProjeto.NOVO)
            .foiProcessado(false)
            .dataCriacao(LocalDateTime.now())
            .build();

        projetoRepository.saveAndFlush(projeto);

        Projeto salvo = projetoRepository.findById(2003859663883L).orElseThrow();
        assertEquals(subcategoriaLonga, salvo.getSubcategoria());
    }
}
