package com.example.ontology.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * keyset 分页游标。
 *
 * <p>游标对调用方是不透明字符串，内部是 {"id": 最后一条 object_id, "v": 排序键值}。
 * 兼容契约示例里的裸 object_id（默认按 object_id 排序时可直接传）。</p>
 */
public final class Cursor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cursor() {
    }

    public record Token(String objectId, String sortValue) {
    }

    /** 编码为 base64url（不带填充）。sortValue 允许为 null（表示排序键为 NULL）。 */
    public static String encode(String objectId, String sortValue, boolean hasSortValue) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", objectId);
        if (hasSortValue) {
            node.put("v", sortValue);
        } else {
            node.putNull("v");
        }
        byte[] json = node.toString().getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    /**
     * 解码游标；无法解码时按"裸 object_id"处理（契约示例 {@code "Order:ORD-000123"}）。
     */
    public static Token decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(trimmed);
            JsonNode node = MAPPER.readTree(raw);
            if (node.isObject() && node.hasNonNull("id")) {
                String v = node.has("v") && !node.get("v").isNull() ? node.get("v").asText() : null;
                return new Token(node.get("id").asText(), v);
            }
        } catch (IllegalArgumentException | java.io.IOException ignored) {
            // 不是 base64 JSON，落到下面的裸 ID 分支
        }
        return new Token(trimmed, null);
    }
}
