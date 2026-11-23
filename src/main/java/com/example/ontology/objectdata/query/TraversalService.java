package com.example.ontology.objectdata.query;

import com.example.ontology.common.OntologyException;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.objectdata.store.LinkRow;
import com.example.ontology.objectdata.store.ObjectRow;
import com.example.ontology.objectdata.store.ObjectStore;
import com.example.ontology.security.OntologyAuthorizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 多跳遍历：从一个对象出发，按给定的链接路径走 N 跳。
 *
 * <p>这是"本体"区别于"带外键的表"的关键能力：{@code Delay:D001} 通过
 * {@code DelayedBy(IN) → OperatedBy(OUT)} 能一路走到 Airline，而不需要调用方
 * 手动调三次接口再自己拼结果。</p>
 *
 * <p>硬限制（防止一个"从 PEK 出发"把全库拉出来）：深度 ≤ {@value #MAX_DEPTH}、
 * 每跳 ≤ {@value #MAX_PER_STEP}，超出截断并在返回里标记 {@code truncated}。</p>
 */
@Service
public class TraversalService {

    public static final int MAX_DEPTH = 3;
    public static final int MAX_PER_STEP = 100;

    private final ObjectStore store;
    private final MetamodelRegistry registry;
    private final OntologyAuthorizer authorizer;

    public TraversalService(ObjectStore store, MetamodelRegistry registry, OntologyAuthorizer authorizer) {
        this.store = store;
        this.registry = registry;
        this.authorizer = authorizer;
    }

    public record StepDef(String linkType, String direction) {
    }

    public record Request(List<StepDef> path, Integer limit) {
    }

    /** 路径上的一个节点。viaLinkId 是走到它用的那条链接，便于前端画路径。 */
    public record Node(String objectId, String typeCode, String businessKey, String title,
                       long version, String viaLinkId) {
    }

    public record Step(String linkType, String direction, String targetType,
                       int count, boolean truncated, List<Node> items) {
    }

    public record Result(String startObjectId, String startType, List<Step> steps,
                         List<Node> finalNodes, boolean truncated) {
    }

    public Result traverse(String startObjectId, List<StepDef> path, Integer limit) {
        ObjectRow start = store.findObject(startObjectId)
                .orElseThrow(() -> OntologyException.objectNotFound(startObjectId));
        authorizer.requireReadType(start.typeCode());

        if (path == null || path.isEmpty()) {
            throw OntologyException.badRequest("path 不能为空，至少一跳");
        }
        if (path.size() > MAX_DEPTH) {
            throw OntologyException.badRequest("遍历深度上限 " + MAX_DEPTH + "，收到 " + path.size());
        }
        int perStep = limit == null ? MAX_PER_STEP : limit;
        if (perStep < 1) {
            throw OntologyException.badRequest("limit 必须大于 0");
        }
        perStep = Math.min(perStep, MAX_PER_STEP);

        LinkedHashSet<String> frontier = new LinkedHashSet<>();
        frontier.add(startObjectId);

        List<Step> steps = new ArrayList<>();
        boolean anyTruncated = false;

        for (StepDef raw : path) {
            if (raw == null || raw.linkType() == null || raw.linkType().isBlank()) {
                throw OntologyException.badRequest("每一步都必须提供 linkType");
            }
            LinkTypeDef link = registry.findLinkType(raw.linkType());
            if (link == null) {
                throw OntologyException.badRequest("链接类型不存在: " + raw.linkType());
            }
            String dir = "IN".equalsIgnoreCase(raw.direction()) ? "IN" : "OUT";
            String targetType = "IN".equals(dir) ? link.fromTypeCode() : link.toTypeCode();
            // 每跳都要校验：viewer 不能借遍历"穿透"到不可读的类型
            authorizer.requireReadType(targetType);

            LinkedHashMap<String, String> otherToLink = new LinkedHashMap<>();
            boolean stepTruncated = false;

            for (String id : frontier) {
                List<LinkRow> rows = store.findLinks(id, link.code(), dir, perStep + 1, null);
                for (LinkRow row : rows) {
                    String other = "IN".equals(dir) ? row.fromObjectId() : row.toObjectId();
                    if (other.equals(id)) {
                        continue;
                    }
                    if (otherToLink.containsKey(other)) {
                        continue;
                    }
                    if (otherToLink.size() >= perStep) {
                        stepTruncated = true;
                        break;
                    }
                    otherToLink.put(other, row.linkId());
                }
                if (stepTruncated) {
                    break;
                }
            }

            Map<String, ObjectRow> loaded = new HashMap<>();
            if (!otherToLink.isEmpty()) {
                for (ObjectRow row : store.findObjects(otherToLink.keySet())) {
                    loaded.put(row.objectId(), row);
                }
            }

            List<Node> nodes = new ArrayList<>();
            for (Map.Entry<String, String> entry : otherToLink.entrySet()) {
                ObjectRow row = loaded.get(entry.getKey());
                if (row == null) {
                    continue;
                }
                if (!authorizer.canReadType(row.typeCode())) {
                    continue;
                }
                nodes.add(new Node(row.objectId(), row.typeCode(), row.businessKey(), row.title(),
                        row.rowVersion(), entry.getValue()));
            }

            steps.add(new Step(link.code(), dir, targetType, nodes.size(), stepTruncated, nodes));
            anyTruncated = anyTruncated || stepTruncated;

            frontier = new LinkedHashSet<>();
            for (Node node : nodes) {
                frontier.add(node.objectId());
            }
            if (frontier.isEmpty()) {
                break;
            }
        }

        List<Node> finals = steps.isEmpty() ? List.of() : steps.get(steps.size() - 1).items();
        return new Result(startObjectId, start.typeCode(), steps, finals, anyTruncated);
    }
}
