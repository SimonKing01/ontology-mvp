package com.example.ontology.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DsSourceRepository extends JpaRepository<DsSource, Long> {

    Optional<DsSource> findByName(String name);

    List<DsSource> findAllByOrderByIdAsc();
}
