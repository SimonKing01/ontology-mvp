package com.example.ontology.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用元模型定义构造器（避免在测试里堆 JSON 字符串）。
 */
public final class TestModel {

    private TestModel() {
    }

    public static Map<String, Object> prop(String key, String type, String owner) {
        return prop(key, type, owner, null, false, null);
    }

    public static Map<String, Object> prop(String key, String type, String owner, String sourcePath,
                                           boolean required, String derivedExpr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("label", key);
        map.put("type", type);
        map.put("owner", owner);
        map.put("required", required);
        if (sourcePath != null) {
            map.put("sourcePath", sourcePath);
        }
        if (derivedExpr != null) {
            map.put("derivedExpr", derivedExpr);
        }
        return map;
    }

    public static Map<String, Object> objectType(String code, String datasetId, String idField,
                                                 String titleField, List<Map<String, Object>> properties) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", code);
        map.put("datasetId", datasetId);
        map.put("idField", idField);
        map.put("titleField", titleField);
        map.put("status", "active");
        map.put("properties", properties);
        return map;
    }

    public static Map<String, Object> linkType(String code, String from, String to, String cardinality,
                                               String fromField, String toField) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", code);
        map.put("fromTypeCode", from);
        map.put("toTypeCode", to);
        map.put("cardinality", cardinality);
        map.put("fromField", fromField);
        map.put("toField", toField);
        map.put("forwardName", code.toLowerCase());
        map.put("reverseName", "reverse");
        return map;
    }

    public static Map<String, Object> param(String name, String type, boolean required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("required", required);
        return map;
    }

    public static Map<String, Object> objectRefParam(String name, String refType, boolean array) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", "objectRef");
        map.put("refType", refType);
        map.put("required", true);
        if (array) {
            map.put("array", true);
        }
        return map;
    }

    public static Map<String, Object> precondition(String type, String prop, Object value, String errorCode,
                                                   String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        if (prop != null) {
            map.put("prop", prop);
        }
        if (value != null) {
            map.put("value", value);
        }
        map.put("errorCode", errorCode);
        map.put("message", message);
        return map;
    }

    public static Map<String, Object> update(String prop, String valueExpr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("op", "update");
        map.put("prop", prop);
        map.put("valueExpr", valueExpr);
        return map;
    }

    public static Map<String, Object> create(String businessKeyExpr, String titleExpr, Map<String, String> values) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("op", "create");
        map.put("businessKeyExpr", businessKeyExpr);
        map.put("titleExpr", titleExpr);
        map.put("values", values);
        return map;
    }

    public static Map<String, Object> action(String code, String targetType, List<Map<String, Object>> params,
                                             List<Map<String, Object>> preconditions,
                                             List<Map<String, Object>> mutations, int maxObjects) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", code);
        map.put("targetTypeCode", targetType);
        map.put("params", params);
        map.put("preconditions", preconditions);
        map.put("mutations", mutations);
        map.put("limits", Map.of("maxObjects", maxObjects));
        return map;
    }

    /** 最小航班模型：flightNo(source) / status(ontology) / reason(ontology)。 */
    public static Map<String, Object> flightType(String code) {
        List<Map<String, Object>> props = new ArrayList<>();
        props.add(prop("flightNo", "string", "source", "flight_no", true, null));
        props.add(prop("status", "string", "ontology"));
        props.add(prop("reason", "string", "ontology"));
        props.add(prop("delayMinutes", "integer", "ontology"));
        return objectType(code, "ds_flight", "flightNo", "flightNo", props);
    }

    public static List<Map<String, Object>> cancelMutations() {
        List<Map<String, Object>> mutations = new ArrayList<>();
        mutations.add(update("status", "literal:'CANCELLED'"));
        mutations.add(update("reason", "param:reason"));
        return mutations;
    }
}
