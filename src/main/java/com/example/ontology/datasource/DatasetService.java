package com.example.ontology.datasource;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 数据集服务：对象类型通过 datasetId 映射到这里。
 */
@Service
public class DatasetService {

    private final DsDatasetRepository repository;
    private final DatasourceService datasourceService;
    private final ObjectMapper mapper;

    public DatasetService(DsDatasetRepository repository, DatasourceService datasourceService, ObjectMapper mapper) {
        this.repository = repository;
        this.datasourceService = datasourceService;
        this.mapper = mapper;
    }

    @Transactional
    public DatasourceDtos.DatasetView create(DatasourceDtos.DatasetRequest req) {
        if (req.id() == null || req.id().isBlank()) {
            throw OntologyException.badRequest("数据集 id 不能为空（对象类型用它做 datasetId）");
        }
        if (req.table() == null || req.table().isBlank()) {
            throw OntologyException.badRequest("数据集 table 不能为空");
        }
        if (repository.existsById(req.id())) {
            throw OntologyException.conflict("数据集已存在: " + req.id(), Map.of("id", req.id()));
        }
        datasourceService.require(req.sourceId());
        int maxRows = req.maxRows() == null ? 10000 : req.maxRows();
        if (maxRows <= 0) {
            throw OntologyException.badRequest("maxRows 必须大于 0");
        }
        String columnsJson;
        try {
            columnsJson = req.columns() == null ? "[]" : mapper.writeValueAsString(req.columns());
        } catch (JsonProcessingException ex) {
            throw OntologyException.badRequest("columns 无法序列化: " + ex.getMessage());
        }
        DsDataset saved = repository.save(DsDataset.create(req.id().trim(), req.name() == null ? req.id() : req.name(),
                req.sourceId(), req.schema(), req.table(), req.keyFields(), columnsJson, maxRows));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<DatasourceDtos.DatasetView> list() {
        return repository.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DsDataset require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new OntologyException(ErrorCode.DATASET_NOT_FOUND,
                        "数据集不存在: " + id, Map.of("datasetId", id)));
    }

    private DatasourceDtos.DatasetView view(DsDataset d) {
        try {
            return new DatasourceDtos.DatasetView(d.getId(), d.getName(), d.getSourceId(), d.getSchemaName(),
                    d.getTableName(), d.getKeyFields(),
                    d.getColumnsJson() == null ? mapper.createArrayNode() : mapper.readTree(d.getColumnsJson()),
                    d.getMaxRows());
        } catch (JsonProcessingException ex) {
            throw new OntologyException(ErrorCode.INTERNAL, "数据集 columns 损坏: " + ex.getMessage());
        }
    }
}
