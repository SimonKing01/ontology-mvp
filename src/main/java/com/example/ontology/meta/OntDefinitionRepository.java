package com.example.ontology.meta;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OntDefinitionRepository extends JpaRepository<OntDefinition, Long> {

    Optional<OntDefinition> findByKindAndCode(String kind, String code);

    List<OntDefinition> findByKindOrderByCodeAsc(String kind);

    List<OntDefinition> findAll();
}
