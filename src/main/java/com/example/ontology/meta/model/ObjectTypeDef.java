package com.example.ontology.meta.model;

import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象类型 = 数据源映射，不是表、不是实体类。
 *
 * <p>它把 dataset（一张物理表/视图）+ idField + titleField + properties[]（每个属性有 sourcePath）
 * 组合成一个可查询、可被 Action 操作的业务对象。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ObjectTypeDef(
        String code,
        String name,
        String datasetId,
        String idField,
        String titleField,
        ObjectTypeStatus status,
        List<PropertyDef> properties
) {

    public List<PropertyDef> propertiesOrEmpty() {
        return properties == null ? List.of() : properties;
    }

    public ObjectTypeStatus statusOrDefault() {
        return status == null ? ObjectTypeStatus.EXPERIMENTAL : status;
    }

    public PropertyDef find(String key) {
        return propertiesOrEmpty().stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    public PropertyDef require(String key) {
        PropertyDef p = find(key);
        if (p == null) {
            throw OntologyException.badRequest("对象类型 " + code + " 不存在属性: " + key);
        }
        return p;
    }

    /** 所有 ontology-owned 属性（只有这些能被 Action 写）。 */
    public List<PropertyDef> ontologyOwnedProperties() {
        List<PropertyDef> out = new ArrayList<>();
        for (PropertyDef p : propertiesOrEmpty()) {
            if (p.owner() == PropertyOwner.ONTOLOGY) {
                out.add(p);
            }
        }
        return out;
    }
}
