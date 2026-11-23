package com.example.ontology.meta;

import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.meta.model.ObjectTypeDef;

import java.util.Map;

/**
 * 某一时刻的元模型快照（内存只读视图）。写入成功后整体替换，读路径无锁。
 */
public record MetamodelSnapshot(
        Map<String, ObjectTypeDef> objectTypes,
        Map<String, LinkTypeDef> linkTypes,
        Map<String, ActionTypeDef> actions,
        Map<String, Integer> revisions
) {

    public static MetamodelSnapshot empty() {
        return new MetamodelSnapshot(Map.of(), Map.of(), Map.of(), Map.of());
    }

    public int revision(DefinitionKind kind, String code) {
        return revisions.getOrDefault(kind.dbValue() + "/" + code, 0);
    }
}
