package com.example.ontology.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常：携带错误码、HTTP 状态与结构化细节。
 */
public class OntologyException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public OntologyException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public OntologyException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static OntologyException badRequest(String message) {
        return new OntologyException(ErrorCode.PARAM_INVALID, message);
    }

    public static OntologyException badRequest(String message, Map<String, Object> details) {
        return new OntologyException(ErrorCode.PARAM_INVALID, message, details);
    }

    public static OntologyException forbidden(String message) {
        return new OntologyException(ErrorCode.FORBIDDEN, message);
    }

    public static OntologyException objectNotFound(String objectId) {
        return new OntologyException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在: " + objectId,
                Map.of("objectId", objectId));
    }

    public static OntologyException definitionNotFound(String kind, String code) {
        return new OntologyException(ErrorCode.DEFINITION_NOT_FOUND,
                "元模型定义不存在: " + kind + "/" + code, Map.of("kind", kind, "code", code));
    }

    public static OntologyException conflict(String message, Map<String, Object> details) {
        return new OntologyException(ErrorCode.CONFLICT, message, details);
    }

    public static OntologyException precondition(String message, Map<String, Object> details) {
        return new OntologyException(ErrorCode.PRECONDITION_FAILED, message, details);
    }

    public static OntologyException metamodel(String message, Map<String, Object> details) {
        return new OntologyException(ErrorCode.METAMODEL_INVALID, message, details);
    }

    /** 便捷构造：带 errorCode 的 422 */
    public static OntologyException preconditionFailed(String actorErrorCode, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("errorCode", actorErrorCode);
        return new OntologyException(ErrorCode.PRECONDITION_FAILED, message, d);
    }
}
