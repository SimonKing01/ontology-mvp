package com.example.ontology.objectdata.query;

import com.example.ontology.objectdata.store.LinkRow;
import com.example.ontology.objectdata.store.ObjectRow;
import com.example.ontology.objectdata.store.ObjectStore;
import com.example.ontology.common.OntologyException;
import com.example.ontology.common.PageResult;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.objectdata.query.ObjectQueryDtos.LinkItem;
import com.example.ontology.security.OntologyAuthorizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一跳链接遍历（只做一跳；多步路径是下一期）。
 */
@Service
public class LinkQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectStore store;
    private final MetamodelRegistry registry;
    private final OntologyAuthorizer authorizer;

    public LinkQueryService(ObjectStore store, MetamodelRegistry registry, OntologyAuthorizer authorizer) {
        this.store = store;
        this.registry = registry;
        this.authorizer = authorizer;
    }

    public PageResult<LinkItem> links(String objectId, String linkType, String direction,
                                      Integer pageSize, String cursor) {
        ObjectRow head = store.findObject(objectId)
                .orElseThrow(() -> OntologyException.objectNotFound(objectId));
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw OntologyException.badRequest("pageSize 取值范围 1.." + MAX_PAGE_SIZE);
        }
        String dir = direction == null || direction.isBlank()
                ? "OUT" : direction.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("OUT", "IN").contains(dir)) {
            throw OntologyException.badRequest("direction 只支持 OUT | IN");
        }

        List<LinkTypeDef> involved = new ArrayList<>();
        if (linkType != null && !linkType.isBlank()) {
            LinkTypeDef def = registry.linkType(linkType);
            if (!head.typeCode().equals(def.fromTypeCode()) && !head.typeCode().equals(def.toTypeCode())) {
                throw OntologyException.badRequest(
                        "链接类型 " + linkType + " 与对象类型 " + head.typeCode() + " 无关");
            }
            involved.add(def);
        } else {
            for (LinkTypeDef def : registry.linkTypes()) {
                if (head.typeCode().equals(def.fromTypeCode()) || head.typeCode().equals(def.toTypeCode())) {
                    involved.add(def);
                }
            }
        }
        if (involved.isEmpty()) {
            return PageResult.of(List.of(), null, false);
        }
        // 目标类型也必须可读，否则不允许通过链接"绕过"类型权限
        for (LinkTypeDef def : involved) {
            String otherType = head.typeCode().equals(def.fromTypeCode()) ? def.toTypeCode() : def.fromTypeCode();
            authorizer.requireReadType(otherType);
        }

        List<LinkRow> rows = store.findLinks(objectId, linkType, dir, size + 1, cursor);
        boolean hasMore = rows.size() > size;
        List<LinkRow> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : rows;

        List<String> neighborIds = page.stream()
                .map(l -> l.fromObjectId().equals(objectId) ? l.toObjectId() : l.fromObjectId())
                .distinct()
                .toList();
        Map<String, ObjectRow> neighbors = new LinkedHashMap<>();
        for (ObjectRow row : store.findObjects(neighborIds)) {
            neighbors.put(row.objectId(), row);
        }

        List<LinkItem> items = new ArrayList<>(page.size());
        for (LinkRow link : page) {
            String neighborId = link.fromObjectId().equals(objectId) ? link.toObjectId() : link.fromObjectId();
            ObjectRow neighbor = neighbors.get(neighborId);
            items.add(new LinkItem(link.linkId(), link.linkType(), dir, neighborId,
                    neighbor == null ? ObjectStore.typeOf(neighborId) : neighbor.typeCode(),
                    neighbor == null ? ObjectStore.businessKeyOf(neighborId) : neighbor.businessKey(),
                    neighbor == null ? null : neighbor.title(),
                    neighbor == null ? null : neighbor.rowVersion()));
        }
        String nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).linkId() : null;
        return PageResult.of(items, nextCursor, hasMore);
    }
}
