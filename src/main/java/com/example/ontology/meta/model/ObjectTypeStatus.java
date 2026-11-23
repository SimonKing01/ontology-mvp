package com.example.ontology.meta.model;

import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 对象类型状态。MVP 只允许三态。 */
public enum ObjectTypeStatus {

    EXPERIMENTAL("experimental"),
    ACTIVE("active"),
    DEPRECATED("deprecated");

    private final String json;

    ObjectTypeStatus(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ObjectTypeStatus from(String raw) {
        if (raw != null) {
            for (ObjectTypeStatus s : values()) {
                if (s.json.equalsIgnoreCase(raw.trim())) {
                    return s;
                }
            }
        }
        throw OntologyException.badRequest("不支持的对象类型状态: " + raw
                + "（允许值：experimental|active|deprecated）");
    }
}
