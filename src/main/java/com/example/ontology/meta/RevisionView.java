package com.example.ontology.meta;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 历史版本视图。
 */
public record RevisionView(int revision, JsonNode definition, Instant createdAt) {
}
