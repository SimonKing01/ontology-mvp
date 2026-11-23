package com.example.ontology.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Action 审计（act_run / act_edit）。
 *
 * <p>审计与对象变更共用同一个 JDBC 事务：要么都成功，要么都回滚。</p>
 */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record RunRow(String runId, String actionCode, String actor, String idempotencyKey,
                         String paramsJson, String resultJson, String status, String errorCode,
                         String errorMessage, Instant startedAt, Instant finishedAt) {
    }

    public record EditRow(long id, String runId, String objectId, String propKey, String oldValue,
                          String newValue, String editType, Instant createdAt) {
    }

    public void insertPending(String runId, String actionCode, String actor, String idempotencyKey,
                              Map<String, Object> params) {
        jdbc.update("""
                        insert into act_run (run_id, action_code, actor, idempotency_key, params, status, started_at)
                        values (:runId, :action, :actor, :key, cast(:params as jsonb), 'PENDING', now())
                        """,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("action", actionCode)
                        .addValue("actor", actor)
                        .addValue("key", idempotencyKey)
                        .addValue("params", toJson(params)));
    }

    public void markSuccess(String runId, Object result) {
        jdbc.update("""
                        update act_run set status = 'SUCCESS', result = cast(:result as jsonb), finished_at = now()
                        where run_id = :runId
                        """,
                new MapSqlParameterSource("runId", runId).addValue("result", toJson(result)));
    }

    /** 预留：当前同步失败整体回滚，FAILED 状态供下一期异步副作用补偿使用。 */
    public void markFailed(String runId, String errorCode, String message) {
        jdbc.update("""
                        update act_run set status = 'FAILED', error_code = :code, error_message = :msg, finished_at = now()
                        where run_id = :runId
                        """,
                new MapSqlParameterSource("runId", runId).addValue("code", errorCode).addValue("msg", message));
    }

    public void insertEdit(String runId, String objectId, String propKey, Object oldValue, Object newValue,
                           String editType) {
        jdbc.update("""
                        insert into act_edit (run_id, object_id, prop_key, old_value, new_value, edit_type)
                        values (:runId, :oid, :key, cast(:old as jsonb), cast(:new as jsonb), :type)
                        """,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("oid", objectId)
                        .addValue("key", propKey)
                        .addValue("old", oldValue == null ? null : toJson(oldValue))
                        .addValue("new", newValue == null ? null : toJson(newValue))
                        .addValue("type", editType));
    }

    public Optional<RunRow> findByKey(String actionCode, String actor, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        List<RunRow> rows = jdbc.query("""
                        select * from act_run
                        where action_code = :action and actor = :actor and idempotency_key = :key
                        """,
                new MapSqlParameterSource("action", actionCode).addValue("actor", actor).addValue("key", idempotencyKey),
                (rs, i) -> mapRun(rs));
        return rows.stream().findFirst();
    }

    public Optional<RunRow> findById(String runId) {
        List<RunRow> rows = jdbc.query("select * from act_run where run_id = :id",
                Map.of("id", runId), (rs, i) -> mapRun(rs));
        return rows.stream().findFirst();
    }

    public List<RunRow> listByAction(String actionCode, int limit) {
        return jdbc.query("""
                        select * from act_run where action_code = :action
                        order by started_at desc limit :limit
                        """,
                new MapSqlParameterSource("action", actionCode).addValue("limit", limit),
                (rs, i) -> mapRun(rs));
    }

    public List<RunRow> listByActor(String actor, int limit) {
        return jdbc.query("""
                        select * from act_run where actor = :actor
                        order by started_at desc limit :limit
                        """,
                new MapSqlParameterSource("actor", actor).addValue("limit", limit),
                (rs, i) -> mapRun(rs));
    }

    public List<EditRow> editsOf(String runId) {
        return jdbc.query("""
                        select id, run_id, object_id, prop_key, old_value, new_value, edit_type, created_at
                        from act_edit where run_id = :runId order by id
                        """,
                Map.of("runId", runId), (rs, i) -> new EditRow(
                        rs.getLong("id"), rs.getString("run_id"), rs.getString("object_id"),
                        rs.getString("prop_key"), rs.getString("old_value"), rs.getString("new_value"),
                        rs.getString("edit_type"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    public List<EditRow> editsOfObject(String objectId, int limit) {
        return jdbc.query("""
                        select id, run_id, object_id, prop_key, old_value, new_value, edit_type, created_at
                        from act_edit where object_id = :oid order by id desc limit :limit
                        """,
                new MapSqlParameterSource("oid", objectId).addValue("limit", limit),
                (rs, i) -> new EditRow(
                        rs.getLong("id"), rs.getString("run_id"), rs.getString("object_id"),
                        rs.getString("prop_key"), rs.getString("old_value"), rs.getString("new_value"),
                        rs.getString("edit_type"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    public long countEdits(String runId) {
        Long n = jdbc.queryForObject("select count(*) from act_edit where run_id = :runId",
                Map.of("runId", runId), Long.class);
        return n == null ? 0 : n;
    }

    public long countEditsOfObject(String objectId) {
        Long n = jdbc.queryForObject("select count(*) from act_edit where object_id = :oid",
                Map.of("oid", objectId), Long.class);
        return n == null ? 0 : n;
    }

    private RunRow mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new RunRow(rs.getString("run_id"), rs.getString("action_code"), rs.getString("actor"),
                rs.getString("idempotency_key"), rs.getString("params"), rs.getString("result"),
                rs.getString("status"), rs.getString("error_code"), rs.getString("error_message"),
                started == null ? null : started.toInstant(),
                finished == null ? null : finished.toInstant());
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("审计数据序列化失败", ex);
        }
    }
}
