package com.example.ontology.meta.model;

import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 属性状态归属 —— 本项目最重要的元数据。
 *
 * <ul>
 *   <li>{@code source}：上游拥有，本体只读；</li>
 *   <li>{@code ontology}：上游没有，本体是唯一真相来源，只有 Action 能改；</li>
 *   <li>{@code derived}：本服务计算得出，永远只读。</li>
 * </ul>
 */
public enum PropertyOwner {

    SOURCE("source"),
    ONTOLOGY("ontology"),
    DERIVED("derived");

    private final String json;

    PropertyOwner(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static PropertyOwner from(String raw) {
        if (raw != null) {
            for (PropertyOwner o : values()) {
                if (o.json.equalsIgnoreCase(raw.trim())) {
                    return o;
                }
            }
        }
        throw OntologyException.badRequest("不支持的属性 owner: " + raw
                + "（允许值：source|ontology|derived）");
    }
}
