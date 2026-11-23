package com.example.ontology.meta.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 链接类型定义。
 *
 * <p>fromField / toField 是两侧数据集的**源列名**：采集阶段据此在 SQL 里做 JOIN，
 * 把链接物化进 obj_link，而不是运行期猜关系。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LinkTypeDef(
        String code,
        String name,
        String fromTypeCode,
        String toTypeCode,
        Cardinality cardinality,
        String fromField,
        String toField,
        String forwardName,
        String reverseName
) {
}
