package com.example.ontology.meta.model;

import com.example.ontology.common.PropertyType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 属性定义。
 *
 * @param key         属性键（英文，稳定，不可随意改名）
 * @param label       展示名
 * @param sourcePath  源列路径（owner=source 必填；owner=ontology 必须为空）
 * @param type        类型
 * @param required    是否必填
 * @param owner       状态归属（source|ontology|derived）
 * @param isArray     是否多值（多值按 ord 展开存 obj_prop）
 * @param derivedExpr 派生表达式（owner=derived 必填）：src:列名 / link:LinkType.prop / count:LinkType / sum:LinkType.prop
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PropertyDef(
        String key,
        String label,
        String sourcePath,
        PropertyType type,
        boolean required,
        PropertyOwner owner,
        @JsonAlias("array") boolean isArray,
        String derivedExpr
) {
}
