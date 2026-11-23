package com.example.ontology.action;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审计查询与视图转换。
 */
@Service
public class ReportService {

    private final AuditService audit;
    private final ObjectMapper mapper;

    public ReportService(AuditService audit, ObjectMapper mapper) {
        this.audit = audit;
        this.mapper = mapper;
    }

    public ActionDtos.RunDetail run(String runId) {
        AuditService.RunRow run = audit.findById(runId)
                .orElseThrow(() -> new OntologyException(ErrorCode.OBJECT_NOT_FOUND,
                        "执行记录不存在: " + runId, Map.of("runId", runId)));
        List<ActionDtos.EditView> edits = new ArrayList<>();
        for (AuditService.EditRow row : audit.editsOf(runId)) {
            edits.add(new ActionDtos.EditView(row.id(), row.runId(), row.objectId(), row.propKey(),
                    read(row.oldValue()), read(row.newValue()), row.editType(), row.createdAt()));
        }
        return new ActionDtos.RunDetail(view(run), edits);
    }

    public List<ActionDtos.RunView> runsOfAction(String actionCode, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        List<ActionDtos.RunView> out = new ArrayList<>();
        for (AuditService.RunRow run : audit.listByAction(actionCode, size)) {
            out.add(view(run));
        }
        return out;
    }

    public List<ActionDtos.EditView> editsOfObject(String objectId, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        List<ActionDtos.EditView> out = new ArrayList<>();
        for (AuditService.EditRow row : audit.editsOfObject(objectId, size)) {
            out.add(new ActionDtos.EditView(row.id(), row.runId(), row.objectId(), row.propKey(),
                    read(row.oldValue()), read(row.newValue()), row.editType(), row.createdAt()));
        }
        return out;
    }

    private ActionDtos.RunView view(AuditService.RunRow run) {
        return new ActionDtos.RunView(run.runId(), run.actionCode(), run.actor(), run.idempotencyKey(),
                run.status(), read(run.paramsJson()), read(run.resultJson()),
                run.errorCode(), run.errorMessage(), run.startedAt(), run.finishedAt());
    }

    private JsonNode read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }
}
