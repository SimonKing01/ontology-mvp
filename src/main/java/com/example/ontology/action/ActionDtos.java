package com.example.ontology.action;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Action 执行与审计的 API 载荷。
 */
public final class ActionDtos {

    private ActionDtos() {
    }

    /**
     * @param params           Action 声明的参数
     * @param expectedVersion  乐观锁：单对象场景的期望版本
     * @param expectedVersions 乐观锁：批量场景按 objectId 给期望版本
     * @param idempotencyKey   幂等键；(action_code, actor, idempotency_key) 唯一
     */
    public record ExecuteRequest(
            java.util.Map<String, Object> params,
            Long expectedVersion,
            java.util.Map<String, Long> expectedVersions,
            String idempotencyKey
    ) {
    }

    public record ExecuteResponse(
            String runId,
            String actionCode,
            String status,
            boolean idempotentReplay,
            JsonNode result
    ) {
    }

    public record RunView(
            String runId,
            String actionCode,
            String actor,
            String idempotencyKey,
            String status,
            JsonNode params,
            JsonNode result,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    public record EditView(
            long id,
            String runId,
            String objectId,
            String propKey,
            JsonNode oldValue,
            JsonNode newValue,
            String editType,
            Instant createdAt
    ) {
    }

    public record RunDetail(RunView run, List<EditView> edits) {
    }
}
