package com.example.ontology.meta;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OntDefinitionHistoryRepository extends JpaRepository<OntDefinitionHistory, Long> {

    List<OntDefinitionHistory> findByKindAndCodeOrderByRevisionDesc(String kind, String code);

    Optional<OntDefinitionHistory> findByKindAndCodeAndRevision(String kind, String code, int revision);
}
