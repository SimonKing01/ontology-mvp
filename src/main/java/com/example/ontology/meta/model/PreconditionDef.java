package com.example.ontology.meta.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 前置条件定义（声明式，不允许写代码）。
 *
 * <p>支持的条件类型：</p>
 * <ul>
 *   <li>equals / notEquals / in / notIn / gt / gte / lt / lte / notNull / isNull —— 作用于目标对象属性</li>
 *   <li>hasLink / notHasLink —— 目标对象在指定方向上是否存在链接</li>
 *   <li>relatedCountLt —— subject（param:x 或 target）在指定方向上关联对象数必须小于 value</li>
 * </ul>
 *
 * @param errorCode 失败时返回给调用方的机器可读原因，必填
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreconditionDef(
        String type,
        String prop,
        Object value,
        String errorCode,
        String message,
        String linkType,
        String direction,
        String subject
) {
}
