package com.example.ontology.meta.model;

import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 链接基数。 */
public enum Cardinality {

    ONE_TO_ONE("1:1"),
    ONE_TO_MANY("1:N"),
    MANY_TO_ONE("N:1"),
    MANY_TO_MANY("N:M");

    private final String json;

    Cardinality(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static Cardinality from(String raw) {
        if (raw != null) {
            for (Cardinality c : values()) {
                if (c.json.equalsIgnoreCase(raw.trim())) {
                    return c;
                }
            }
        }
        throw OntologyException.badRequest("不支持的 cardinality: " + raw + "（允许值：1:1|1:N|N:1|N:M）");
    }
}
