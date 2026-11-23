package com.example.ontology.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DsDatasetRepository extends JpaRepository<DsDataset, String> {

    List<DsDataset> findAllByOrderByIdAsc();

    List<DsDataset> findBySourceId(Long sourceId);
}
