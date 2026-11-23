package com.example.ontology.meta.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Action 定义：本体唯一的写入口。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionTypeDef(
        String code,
        String name,
        String targetTypeCode,
        List<ActionParamDef> params,
        List<PreconditionDef> preconditions,
        List<MutationDef> mutations,
        LimitsDef limits
) {

    public List<ActionParamDef> paramsOrEmpty() {
        return params == null ? List.of() : params;
    }

    public List<PreconditionDef> preconditionsOrEmpty() {
        return preconditions == null ? List.of() : preconditions;
    }

    public List<MutationDef> mutationsOrEmpty() {
        return mutations == null ? List.of() : mutations;
    }

    public int maxObjects() {
        return limits == null ? 1000 : limits.maxObjectsOrDefault();
    }

    public ActionParamDef findParam(String name) {
        return paramsOrEmpty().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }
}
