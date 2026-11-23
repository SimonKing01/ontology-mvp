package com.example.ontology.meta.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * 变更定义。op 取值：
 *
 * <ul>
 *   <li>{@code update}：改一个 ontology-owned 属性，valueExpr 支持 literal:'x' / param:x / now()</li>
 *   <li>{@code create}：新建对象，businessKeyExpr 必填，values 里只允许 ontology-owned 属性</li>
 *   <li>{@code createLink} / {@code deleteLink}：写/删链接，to 支持 param:x 或 literal:'...'</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MutationDef(
        String op,
        String prop,
        String valueExpr,
        String businessKeyExpr,
        String titleExpr,
        Map<String, String> values,
        String linkType,
        String to,
        Map<String, String> linkProperties
) {
}
