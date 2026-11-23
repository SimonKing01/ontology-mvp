package com.example.ontology.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常映射：保证任何失败都返回 {code, message, details} 结构，且机器可读。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OntologyException.class)
    public ResponseEntity<ApiError> handleOntology(OntologyException ex, HttpServletRequest req) {
        ErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, ex.getMessage(), ex.details(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.PARAM_INVALID, "请求体校验失败", details, req.getRequestURI()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadBody(Exception ex, HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.PARAM_INVALID, "请求无法解析: " + ex.getMessage(),
                        Map.of(), req.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(403)
                .body(ApiError.of(ErrorCode.FORBIDDEN, "无权限执行该操作", Map.of(), req.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex, HttpServletRequest req) {
        return ResponseEntity.status(401)
                .body(ApiError.of(ErrorCode.FORBIDDEN, "未认证", Map.of(), req.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("数据完整性冲突: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(409)
                .body(ApiError.of(ErrorCode.CONFLICT, "违反唯一约束或引用约束",
                        Map.of("cause", String.valueOf(ex.getMostSpecificCause().getMessage())),
                        req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest req) {
        log.error("未预期错误", ex);
        return ResponseEntity.status(500)
                .body(ApiError.of(ErrorCode.INTERNAL, "服务内部错误", Map.of(), req.getRequestURI()));
    }
}
