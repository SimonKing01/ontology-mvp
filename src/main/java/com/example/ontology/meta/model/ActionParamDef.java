package com.example.ontology.meta.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Action 参数定义。
 *
 * @param type      string|integer|long|decimal|boolean|date|timestamp|objectRef
 * @param refType   type=objectRef 时必填，声明引用的对象类型
 * @param array     objectRef 是否多值（批量 Action 用）
 * @param maxLength 字符串长度上限
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionParamDef(
        String name,
        String type,
        boolean required,
        String refType,
        @JsonAlias("isArray") boolean array,
        Integer maxLength
) {

    public boolean isObjectRef() {
        return "objectRef".equalsIgnoreCase(type);
    }
}
