package com.example.ontology.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        Map<String, Object> details,
        String path,
        Instant timestamp
) {
    public static ApiError of(ErrorCode code, String message, Map<String, Object> details, String path) {
        return new ApiError(code.name(), message, details, path, Instant.now());
    }
}
