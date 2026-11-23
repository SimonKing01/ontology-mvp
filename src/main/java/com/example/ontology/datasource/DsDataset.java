package com.example.ontology.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 数据集：对象类型映射的目标（一张物理表 / 视图）。
 */
@Entity
@Table(name = "ds_dataset")
public class DsDataset {

    @Id
    @Column(length = 80)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    private String schemaName;

    @Column(nullable = false, length = 120)
    private String tableName;

    private String keyFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String columnsJson;

    @Column(nullable = false)
    private int maxRows;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DsDataset() {
    }

    public static DsDataset create(String id, String name, Long sourceId, String schemaName, String tableName,
                                   String keyFields, String columnsJson, int maxRows) {
        DsDataset d = new DsDataset();
        d.id = id;
        d.name = name;
        d.sourceId = sourceId;
        d.schemaName = schemaName;
        d.tableName = tableName;
        d.keyFields = keyFields;
        d.columnsJson = columnsJson;
        d.maxRows = maxRows;
        d.createdAt = Instant.now();
        d.updatedAt = Instant.now();
        return d;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getKeyFields() {
        return keyFields;
    }

    public String getColumnsJson() {
        return columnsJson;
    }

    public int getMaxRows() {
        return maxRows;
    }
}
