package com.example.ontology.objectdata;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;
import java.util.Map;

/**
 * 查询 API 的载荷。
 */
public final class ObjectQueryDtos {

    private ObjectQueryDtos() {
    }

    public record FilterDef(String prop, String op, Object value) {
    }

    /** 契约里排序用 {"prop":"createdAt","op":"desc"}；同时兼容 direction 字段。 */
    public record SortDef(String prop, @JsonAlias("direction") String op) {
    }

    public record QueryRequest(
            String typeCode,
            List<FilterDef> filters,
            List<SortDef> sort,
            Integer pageSize,
            String cursor,
            List<String> fields
    ) {
    }

    public record CountRequest(String typeCode, List<FilterDef> filters) {
    }

    public record ObjectItem(
            String objectId,
            String typeCode,
            String businessKey,
            long version,
            String stateOwner,
            String title,
            boolean stale,
            Map<String, Object> properties
    ) {
    }

    public record LinkItem(
            String linkId,
            String linkType,
            String direction,
            String objectId,
            String typeCode,
            String businessKey,
            String title,
            Long version
    ) {
    }
}
