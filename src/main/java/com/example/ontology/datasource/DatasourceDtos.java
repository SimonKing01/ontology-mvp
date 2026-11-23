package com.example.ontology.datasource;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 数据源 / 数据集的 API 载荷。
 */
public final class DatasourceDtos {

    private DatasourceDtos() {
    }

    public record SourceRequest(
            String name,
            String kind,
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password
    ) {
    }

    public record SourceView(
            Long id,
            String name,
            String kind,
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            boolean hasPassword,
            Instant createdAt
    ) {
    }

    public record DatasetRequest(
            String id,
            String name,
            Long sourceId,
            String schema,
            String table,
            String keyFields,
            JsonNode columns,
            Integer maxRows
    ) {
    }

    public record DatasetView(
            String id,
            String name,
            Long sourceId,
            String schema,
            String table,
            String keyFields,
            JsonNode columns,
            int maxRows
    ) {
    }
}
