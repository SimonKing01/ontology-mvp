package com.example.ontology.meta;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 元模型定义视图：payload 原样返回 + 当前 revision。
 */
public record DefinitionView(
        String kind,
        String code,
        int revision,
        JsonNode definition,
        Instant updatedAt
) {
}
