package com.example.ontology.meta.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Action 上限。maxObjects 控制单次最多操作对象数，超限必须分片。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LimitsDef(Integer maxObjects) {

    public int maxObjectsOrDefault() {
        return maxObjects == null ? 1000 : maxObjects;
    }
}
