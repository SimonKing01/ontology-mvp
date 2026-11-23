package com.example.ontology.objectdata.store;

import java.time.Instant;

/**
 * obj_object 一行（对象头，不含属性）。属性一律在 obj_prop 里。
 */
public record ObjectRow(
        String objectId,
        String typeCode,
        String businessKey,
        long rowVersion,
        String stateOwner,
        String title,
        Instant createdAt,
        Instant updatedAt
) {
    /** 身份规则：{typeCode}:{businessKey} —— 稳定、可读、可被外部引用（R5）。 */
    public static String idOf(String typeCode, String businessKey) {
        return typeCode + ":" + businessKey;
    }
}
