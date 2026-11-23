package com.example.ontology.action;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import com.example.ontology.config.TransactionConfig;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.ActionParamDef;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.meta.model.MutationDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.meta.model.PropertyOwner;
import com.example.ontology.objectdata.store.ObjectRow;
import com.example.ontology.objectdata.store.ObjectStore;
import com.example.ontology.security.OntologyAuthorizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Action 引擎 —— 系统里<b>唯一</b>的业务写路径（R3）。
 *
 * <p>严格按契约的时序执行，且 1~8 步在同一个 JDBC 事务内：</p>
 * <ol>
 *   <li>幂等检查（同 key 已成功 → 直接返回上次结果）</li>
 *   <li>权限检查</li>
 *   <li>参数校验（类型/必填/长度/objectRef 是否存在）</li>
 *   <li>加载对象并加行锁（ORDER BY object_id FOR UPDATE）</li>
 *   <li>乐观锁校验（版本不一致 → 409）</li>
 *   <li>前置条件（不满足 → 422 + error_code）</li>
 *   <li>应用变更（只允许 ontology-owned 属性）+ act_edit</li>
 *   <li>提交审计（act_run SUCCESS）</li>
 * </ol>
 */
@Service
public class ActionEngine {

    private static final Logger log = LoggerFactory.getLogger(ActionEngine.class);

    private final MetamodelRegistry registry;
    private final OntologyAuthorizer authorizer;
    private final ValueEvaluator evaluator;
    private final PreconditionEvaluator preconditionEvaluator;
    private final ObjectStore store;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ActionEngine(MetamodelRegistry registry,
                        OntologyAuthorizer authorizer,
                        ValueEvaluator evaluator,
                        PreconditionEvaluator preconditionEvaluator,
                        ObjectStore store,
                        AuditService audit,
                        ObjectMapper mapper) {
        this.registry = registry;
        this.authorizer = authorizer;
        this.evaluator = evaluator;
        this.preconditionEvaluator = preconditionEvaluator;
        this.store = store;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(transactionManager = TransactionConfig.JDBC_TX_MANAGER, rollbackFor = Exception.class)
    public ActionDtos.ExecuteResponse executeInTx(String actionCode, ActionDtos.ExecuteRequest request) {
        ActionTypeDef action = registry.action(actionCode);
        String actor = authorizer.currentActor();
        String idempotencyKey = request == null ? null : request.idempotencyKey();

        // ---- 1. 幂等检查 ----
        Optional<AuditService.RunRow> existing = audit.findByKey(actionCode, actor, idempotencyKey);
        if (existing.isPresent()) {
            AuditService.RunRow run = existing.get();
            if ("SUCCESS".equals(run.status())) {
                log.info("Action {} 命中幂等键 {}，直接返回上次结果 runId={}", actionCode, idempotencyKey, run.runId());
                return replay(run);
            }
            throw OntologyException.conflict("同一幂等键的执行尚未完成或已失败，请更换 idempotencyKey",
                    Map.of("code", "IDEMPOTENCY_IN_PROGRESS", "runId", run.runId(), "status", run.status()));
        }
        // 1.5 先写 PENDING 占位：唯一约束把并发重复请求串行化，保证只产生一次副作用
        String runId = UUID.randomUUID().toString().replace("-", "");
        audit.insertPending(runId, actionCode, actor, idempotencyKey,
                request == null ? Map.of() : request.params());

        // ---- 2. 权限 ----
        authorizer.requireExecute(actionCode);

        // ---- 3. 参数校验 ----
        Map<String, Object> params = evaluator.validateParams(action,
                request == null ? Map.of() : request.params());
        ObjectTypeDef type = registry.objectType(action.targetTypeCode());
        List<String> targets = resolveTargets(action, params);
        boolean hasCreate = action.mutationsOrEmpty().stream()
                .anyMatch(m -> "create".equalsIgnoreCase(nz(m.op())));
        if (hasCreate && !targets.isEmpty()) {
            throw OntologyException.badRequest("同一 Action 不能既 create 又操作已有对象");
        }
        if (!hasCreate && targets.isEmpty()) {
            throw OntologyException.badRequest("未解析到目标对象：请通过 objectRef 参数指定");
        }
        if (targets.size() > action.maxObjects()) {
            throw new OntologyException(ErrorCode.LIMIT_EXCEEDED,
                    "超过单次最多操作对象数 " + action.maxObjects() + "，请分片提交",
                    Map.of("maxObjects", action.maxObjects(), "requested", targets.size(),
                            "hint", "按 maxObjects 分批调用，每批一个幂等键"));
        }

        // ---- 4. 加载对象 + 行锁 ----
        List<ObjectRow> locked = store.lockObjects(targets);
        if (locked.size() != targets.size()) {
            for (String id : targets) {
                if (locked.stream().noneMatch(r -> r.objectId().equals(id))) {
                    throw OntologyException.objectNotFound(id);
                }
            }
        }
        // ---- 5. 乐观锁 ----
        verifyVersions(request, locked);

        // ---- 6. 前置条件 ----
        Map<String, Map<String, List<Object>>> propsByObject = store.loadProperties(targets, type);
        for (ObjectRow row : locked) {
            preconditionEvaluator.checkAll(action, type, row,
                    singleValues(propsByObject.get(row.objectId())), params);
        }

        // ---- 7. 应用变更 ----
        List<Map<String, Object>> targetResults = new ArrayList<>();
        List<String> created = new ArrayList<>();
        List<Map<String, Object>> editLog = new ArrayList<>();

        for (MutationDef mutation : action.mutationsOrEmpty()) {
            if ("create".equalsIgnoreCase(nz(mutation.op()))) {
                created.add(applyCreate(mutation, type, params, runId, editLog));
            }
        }
        for (ObjectRow row : locked) {
            Map<String, Object> current = singleValues(propsByObject.get(row.objectId()));
            boolean touched = false;
            for (MutationDef mutation : action.mutationsOrEmpty()) {
                String op = nz(mutation.op()).toLowerCase(Locale.ROOT);
                switch (op) {
                    case "update" -> {
                        applyUpdate(mutation, type, row, current, params, runId, editLog);
                        touched = true;
                    }
                    case "createlink", "deletelink" -> {
                        applyLink(mutation, type, row, params, runId, editLog);
                        touched = true;
                    }
                    default -> {
                        // create 已在上面处理
                    }
                }
            }
            long version = row.rowVersion();
            if (touched) {
                // R6/R9：每次 Action 后 row_version + 1
                version = store.bumpVersion(row.objectId());
            }
            Map<String, Object> targetResult = new LinkedHashMap<>();
            targetResult.put("objectId", row.objectId());
            targetResult.put("version", version);
            targetResults.add(targetResult);
        }

        // ---- 8. 审计 ----
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionCode", actionCode);
        result.put("targets", targetResults);
        result.put("created", created);
        result.put("editCount", editLog.size());
        result.put("edits", editLog);
        result.put("effects", List.of());
        audit.markSuccess(runId, result);

        log.info("Action {} 执行成功 runId={} targets={} created={} edits={}",
                actionCode, runId, targets.size(), created.size(), editLog.size());
        return new ActionDtos.ExecuteResponse(runId, actionCode, "SUCCESS", false,
                mapper.valueToTree(result));
    }

    // ------------------------------------------------------------------
    private List<String> resolveTargets(ActionTypeDef action, Map<String, Object> params) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ActionParamDef def : action.paramsOrEmpty()) {
            if (!def.isObjectRef()) {
                continue;
            }
            // 只有 refType == targetTypeCode 的 objectRef 才是"被操作的目标"，
            // 其余（如 AssignAircraft 的 aircraft）是对端引用，用于链接/前置条件
            if (!action.targetTypeCode().equals(def.refType())) {
                continue;
            }
            Object value = params.get(def.name());
            if (value == null) {
                continue;
            }
            if (def.array()) {
                for (Object item : (List<?>) value) {
                    ids.add(String.valueOf(item));
                }
            } else {
                ids.add(String.valueOf(value));
            }
        }
        for (String id : ids) {
            if (!store.exists(id)) {
                throw OntologyException.objectNotFound(id);
            }
            if (!ObjectStore.typeOf(id).equals(action.targetTypeCode())) {
                throw OntologyException.badRequest("目标对象类型不匹配，期望 "
                        + action.targetTypeCode() + "，收到 " + id);
            }
        }
        return List.copyOf(ids);
    }

    private void verifyVersions(ActionDtos.ExecuteRequest request, List<ObjectRow> locked) {
        Map<String, Long> versions = request == null || request.expectedVersions() == null
                ? Map.of() : request.expectedVersions();
        for (ObjectRow row : locked) {
            Long expected = versions.get(row.objectId());
            if (expected == null && request != null) {
                expected = request.expectedVersion();
            }
            if (expected == null) {
                throw OntologyException.badRequest(
                        "缺少 expectedVersion（乐观锁必填）: " + row.objectId(),
                        Map.of("objectId", row.objectId(), "actualVersion", row.rowVersion()));
            }
            if (expected != row.rowVersion()) {
                throw OntologyException.conflict("版本冲突：对象已被其他请求修改",
                        Map.of("code", "VERSION_CONFLICT",
                                "objectId", row.objectId(),
                                "expectedVersion", expected,
                                "actualVersion", row.rowVersion()));
            }
        }
    }

    private void applyUpdate(MutationDef mutation, ObjectTypeDef type, ObjectRow row, Map<String, Object> current,
                             Map<String, Object> params, String runId, List<Map<String, Object>> editLog) {
        PropertyDef prop = type.require(mutation.prop());
        if (prop.owner() != PropertyOwner.ONTOLOGY) {
            // 启动期已校验；这里是纵深防御：运行期也不允许写非 ontology 属性
            throw new OntologyException(ErrorCode.METAMODEL_INVALID,
                    "违反 R7：Action 试图修改 owner=" + prop.owner().json() + " 的属性 "
                            + type.code() + "." + prop.key());
        }
        Object newValue = evaluator.evaluate(mutation.valueExpr(), params, prop);
        Object oldValue = current.get(prop.key());
        store.setProperty(row.objectId(), prop, 0, newValue);
        audit.insertEdit(runId, row.objectId(), prop.key(), oldValue, newValue, "UPDATE");
        editLog.add(editEntry(row.objectId(), prop.key(), oldValue, newValue, "UPDATE"));
    }

    private String applyCreate(MutationDef mutation, ObjectTypeDef type, Map<String, Object> params,
                               String runId, List<Map<String, Object>> editLog) {
        String businessKey = evaluator.evaluateAsString(mutation.businessKeyExpr(), params).trim();
        if (businessKey.isEmpty()) {
            throw OntologyException.badRequest("businessKeyExpr 求值为空，无法生成稳定身份（R5）");
        }
        String objectId = ObjectRow.idOf(type.code(), businessKey);
        if (store.exists(objectId)) {
            throw OntologyException.conflict("对象已存在: " + objectId,
                    Map.of("code", "OBJECT_EXISTS", "objectId", objectId));
        }
        String title = mutation.titleExpr() == null || mutation.titleExpr().isBlank()
                ? businessKey : evaluator.evaluateAsString(mutation.titleExpr(), params);
        // state_owner=ontology：这条对象由本体创建，上游还没有对应记录
        store.insertActionCreatedObject(objectId, type.code(), businessKey, title);

        Map<String, String> values = mutation.values() == null ? Map.of() : mutation.values();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            PropertyDef prop = type.require(entry.getKey());
            if (prop.owner() != PropertyOwner.ONTOLOGY) {
                throw new OntologyException(ErrorCode.METAMODEL_INVALID,
                        "违反 R7：create 只能初始化 ontology-owned 属性，" + prop.key()
                                + " 的 owner=" + prop.owner().json());
            }
            Object value = evaluator.evaluate(entry.getValue(), params, prop);
            store.setProperty(objectId, prop, 0, value);
            audit.insertEdit(runId, objectId, prop.key(), null, value, "CREATE");
            editLog.add(editEntry(objectId, prop.key(), null, value, "CREATE"));
        }
        return objectId;
    }

    private void applyLink(MutationDef mutation, ObjectTypeDef type, ObjectRow row, Map<String, Object> params,
                           String runId, List<Map<String, Object>> editLog) {
        LinkTypeDef link = registry.linkType(mutation.linkType());
        boolean typeIsFrom = type.code().equals(link.fromTypeCode());
        String otherType = typeIsFrom ? link.toTypeCode() : link.fromTypeCode();

        String otherId = null;
        if (mutation.to() != null && !mutation.to().isBlank()) {
            otherId = evaluator.evaluateAsString(mutation.to(), params);
            if (!store.exists(otherId)) {
                throw OntologyException.objectNotFound(otherId);
            }
            if (!ObjectStore.typeOf(otherId).equals(otherType)) {
                throw OntologyException.badRequest("链接对端类型不匹配，期望 " + otherType + "，收到 " + otherId);
            }
        }

        if ("createLink".equalsIgnoreCase(nz(mutation.op()))) {
            if (otherId == null) {
                throw OntologyException.badRequest("createLink 必须提供 to");
            }
            String from = typeIsFrom ? row.objectId() : otherId;
            String to = typeIsFrom ? otherId : row.objectId();
            Map<String, Object> linkProps = new LinkedHashMap<>();
            if (mutation.linkProperties() != null) {
                linkProps.putAll(mutation.linkProperties());
            }
            store.insertLink(link.code(), from, to, linkProps);
            audit.insertEdit(runId, row.objectId(), link.code(), null, otherId, "LINK_CREATE");
            editLog.add(editEntry(row.objectId(), link.code(), null, otherId, "LINK_CREATE"));
            return;
        }

        String actor = authorizer.currentActor();
        if (otherId == null) {
            if (typeIsFrom) {
                store.deleteLinksOfType(link.code(), row.objectId(), actor, runId);
            } else {
                store.deleteIncomingLinksOfType(link.code(), row.objectId(), actor, runId);
            }
            audit.insertEdit(runId, row.objectId(), link.code(), null, null, "LINK_DELETE");
            editLog.add(editEntry(row.objectId(), link.code(), null, null, "LINK_DELETE"));
            return;
        }
        String from = typeIsFrom ? row.objectId() : otherId;
        String to = typeIsFrom ? otherId : row.objectId();
        store.deleteLink(link.code(), from, to, actor, runId);
        audit.insertEdit(runId, row.objectId(), link.code(), otherId, null, "LINK_DELETE");
        editLog.add(editEntry(row.objectId(), link.code(), otherId, null, "LINK_DELETE"));
    }

    private Map<String, Object> editEntry(String objectId, String propKey, Object oldValue, Object newValue,
                                          String editType) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("objectId", objectId);
        entry.put("propKey", propKey);
        entry.put("oldValue", oldValue);
        entry.put("newValue", newValue);
        entry.put("editType", editType);
        return entry;
    }

    private Map<String, Object> singleValues(Map<String, List<Object>> props) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (props == null) {
            return out;
        }
        props.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                out.put(key, values.get(0));
            }
        });
        return out;
    }

    private ActionDtos.ExecuteResponse replay(AuditService.RunRow run) {
        JsonNode result = null;
        if (run.resultJson() != null) {
            try {
                result = mapper.readTree(run.resultJson());
            } catch (Exception ex) {
                log.warn("幂等重放时解析 result 失败: {}", ex.getMessage());
            }
        }
        return new ActionDtos.ExecuteResponse(run.runId(), run.actionCode(), run.status(), true, result);
    }

    private String nz(String value) {
        return value == null ? "" : value.trim();
    }
}
