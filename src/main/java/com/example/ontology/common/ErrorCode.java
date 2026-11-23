package com.example.ontology.common;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 *
 * <p>契约规定的前 6 个是硬性要求；其余是同一风格下的必要补充（元模型、数据集、Action 定义找不到等），
 * 在 README「错误码」一节中有说明。</p>
 */
public enum ErrorCode {

    /** 400：参数不合法（类型、必填、长度、objectRef 不存在等） */
    PARAM_INVALID(HttpStatus.BAD_REQUEST),
    /** 400：超过 limits.maxObjects，必须分片 */
    LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST),
    /** 401：未认证（HTTP Basic 未提供或错误） */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    /** 403：无权限 */
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 404：对象不存在 */
    OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 404：元模型定义不存在 */
    DEFINITION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 404：数据集 / 数据源不存在 */
    DATASET_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 404：Action 定义不存在 */
    ACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 409：版本冲突或幂等键冲突 */
    CONFLICT(HttpStatus.CONFLICT),
    /** 422：前置条件不满足（details.errorCode 给出机器可读原因） */
    PRECONDITION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 400：元模型本身不合法 */
    METAMODEL_INVALID(HttpStatus.BAD_REQUEST),
    /** 500：未预期错误 */
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
