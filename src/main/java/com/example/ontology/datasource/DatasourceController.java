package com.example.ontology.datasource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源 / 数据集 / 采集接口。
 *
 * <p><b>注意</b>：采集是"把上游状态拉进对象层"，只写 source/derived 属性；
 * 它不是业务写路径，业务状态只能由 Action 修改。</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "数据源与采集", description = "注册上游数据源、映射数据集、全量采集到对象层")
public class DatasourceController {

    private final DatasourceService datasourceService;
    private final DatasetService datasetService;
    private final IngestService ingestService;

    public DatasourceController(DatasourceService datasourceService, DatasetService datasetService,
                                IngestService ingestService) {
        this.datasourceService = datasourceService;
        this.datasetService = datasetService;
        this.ingestService = ingestService;
    }

    @PostMapping("/datasources")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "注册数据源（password 加密后落库，永不回显）")
    public DatasourceDtos.SourceView createSource(@RequestBody DatasourceDtos.SourceRequest request) {
        return datasourceService.create(request);
    }

    @GetMapping("/datasources")
    @Operation(summary = "列出数据源")
    public List<DatasourceDtos.SourceView> listSources() {
        return datasourceService.list();
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "注册数据集（对象类型的 datasetId 指向它）")
    public DatasourceDtos.DatasetView createDataset(@RequestBody DatasourceDtos.DatasetRequest request) {
        return datasetService.create(request);
    }

    @GetMapping("/datasets")
    @Operation(summary = "列出数据集")
    public List<DatasourceDtos.DatasetView> listDatasets() {
        return datasetService.list();
    }

    @PostMapping("/ingest/object-types/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    @Operation(summary = "全量采集一个对象类型（只写 source/derived 属性）")
    public IngestService.IngestReport ingestObjectType(@PathVariable String code) {
        return ingestService.ingestObjectType(code);
    }

    @PostMapping("/ingest/links")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    @Operation(summary = "物化全部链接（按 fromField/toField 在源库 JOIN）")
    public IngestService.LinkReport ingestLinks() {
        return ingestService.rebuildLinks();
    }

    @PostMapping("/ingest/derived")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    @Operation(summary = "重算依赖链接的派生属性")
    public IngestService.DerivedReport ingestDerived() {
        return ingestService.recomputeDerived();
    }

    @PostMapping("/ingest/all")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    @Operation(summary = "一键全量采集：对象 → 链接 → 派生（同一事务）")
    public IngestService.IngestAllReport ingestAll() {
        return ingestService.ingestAll();
    }

    @GetMapping("/ingest/stats")
    @Operation(summary = "对象层规模：对象数 / 属性数 / 链接数")
    public Map<String, Object> stats() {
        return ingestService.layerStats();
    }
}
