package com.example.ontology.action;

import com.example.ontology.common.OntologyException;
import com.example.ontology.security.OntologyAuthorizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Action 执行外壳（非事务）。
 *
 * <p>存在的唯一原因是处理"同一幂等键并发提交"：两个请求同时进来时，
 * 唯一约束会让其中一个失败。失败方在这里被捕获，改为读取已提交的那次执行结果返回，
 * 而不是把一个数据库异常抛给调用方。</p>
 */
@Service
public class ActionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);

    private final ActionEngine engine;
    private final AuditService audit;
    private final OntologyAuthorizer authorizer;
    private final ObjectMapper mapper;

    public ActionExecutionService(ActionEngine engine, AuditService audit,
                                  OntologyAuthorizer authorizer, ObjectMapper mapper) {
        this.engine = engine;
        this.audit = audit;
        this.authorizer = authorizer;
        this.mapper = mapper;
    }

    public ActionDtos.ExecuteResponse execute(String actionCode, ActionDtos.ExecuteRequest request) {
        try {
            return engine.executeInTx(actionCode, request);
        } catch (DataIntegrityViolationException ex) {
            String actor = authorizer.currentActor();
            String key = request == null ? null : request.idempotencyKey();
            Optional<AuditService.RunRow> run = audit.findByKey(actionCode, actor, key);
            if (run.isPresent() && "SUCCESS".equals(run.get().status())) {
                log.info("并发幂等冲突，返回已提交结果 runId={}", run.get().runId());
                JsonNode result = null;
                try {
                    result = run.get().resultJson() == null ? null : mapper.readTree(run.get().resultJson());
                } catch (Exception ignored) {
                    // 结果解析失败不影响幂等语义
                }
                return new ActionDtos.ExecuteResponse(run.get().runId(), actionCode, "SUCCESS", true, result);
            }
            if (run.isPresent()) {
                throw OntologyException.conflict("同一幂等键正在执行，请稍后重试",
                        Map.of("code", "IDEMPOTENCY_IN_PROGRESS", "runId", run.get().runId(),
                                "status", run.get().status()));
            }
            throw OntologyException.conflict("违反唯一约束（可能是对象身份冲突）",
                    Map.of("code", "UNIQUE_VIOLATION",
                            "cause", String.valueOf(ex.getMostSpecificCause().getMessage())));
        }
    }
}
