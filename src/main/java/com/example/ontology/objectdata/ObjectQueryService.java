package com.example.ontology.objectdata;

import com.example.ontology.common.Cursor;
import com.example.ontology.common.OntologyException;
import com.example.ontology.common.PageResult;
import com.example.ontology.common.PropertyType;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.objectdata.ObjectQueryDtos.CountRequest;
import com.example.ontology.objectdata.ObjectQueryDtos.FilterDef;
import com.example.ontology.objectdata.ObjectQueryDtos.ObjectItem;
import com.example.ontology.objectdata.ObjectQueryDtos.QueryRequest;
import com.example.ontology.objectdata.ObjectQueryDtos.SortDef;
import com.example.ontology.security.OntologyAuthorizer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对象查询服务：ACL 裁剪 + 下推查询 + keyset 分页 + 属性投影。
 *
 * <p>一次查询产生两条 SQL：第一条完成"筛选 + 排序 + 分页"（单条 SQL 完成，R4），
 * 第二条按本页 object_id 批量取属性（避免 N+1）。绝不存在"查全量再在内存里过滤"。</p>
 */
@Service
public class ObjectQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final MetamodelRegistry registry;
    private final OntologyAuthorizer authorizer;
    private final ObjectQuerySqlBuilder sqlBuilder;
    private final ObjectStore store;

    public ObjectQueryService(NamedParameterJdbcTemplate jdbc,
                              MetamodelRegistry registry,
                              OntologyAuthorizer authorizer,
                              ObjectQuerySqlBuilder sqlBuilder,
                              ObjectStore store) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.authorizer = authorizer;
        this.sqlBuilder = sqlBuilder;
        this.store = store;
    }

    /** 查询结果的对象头（含排序键，用于生成游标）。 */
    private record Head(String objectId, String typeCode, String businessKey, long version,
                        String stateOwner, String title, Object sortValue) {
    }

    public PageResult<ObjectItem> query(QueryRequest request) {
        if (request == null || request.typeCode() == null || request.typeCode().isBlank()) {
            throw OntologyException.badRequest("typeCode 不能为空");
        }
        ObjectTypeDef type = registry.objectType(request.typeCode());
        authorizer.requireReadType(type.code());

        int pageSize = request.pageSize() == null ? DEFAULT_PAGE_SIZE : request.pageSize();
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw OntologyException.badRequest("pageSize 取值范围 1.." + MAX_PAGE_SIZE);
        }
        List<FilterDef> filters = request.filters() == null ? List.of() : request.filters();
        requireFilterPermission(type, filters);

        if (request.sort() != null && request.sort().size() > 1) {
            throw OntologyException.badRequest("MVP 只支持单字段排序（多字段排序请等下期）");
        }
        SortDef sortDef = request.sort() == null || request.sort().isEmpty() ? null : request.sort().get(0);
        ObjectQuerySqlBuilder.SortSpec sort = sqlBuilder.resolveSort(type, sortDef);
        if (sort != null && sort.joinProp() && !authorizer.canReadField(type.code(), sort.propKey())) {
            throw OntologyException.forbidden("无权按属性排序: " + sort.propKey());
        }

        List<PropertyDef> fields = resolveFields(type, request.fields());
        Cursor.Token cursor = Cursor.decode(request.cursor());

        ObjectQuerySqlBuilder.BuiltQuery query =
                sqlBuilder.buildSelect(type, filters, sort, cursor, pageSize + 1);
        List<Head> rows = jdbc.query(query.sql(), query.params(), (rs, i) -> new Head(
                rs.getString("object_id"),
                rs.getString("type_code"),
                rs.getString("business_key"),
                rs.getLong("row_version"),
                rs.getString("state_owner"),
                rs.getString("title"),
                sort == null ? null : rs.getObject("sort_value")));

        boolean hasMore = rows.size() > pageSize;
        List<Head> page = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : rows;

        List<String> ids = page.stream().map(Head::objectId).toList();
        Map<String, Map<String, List<Object>>> props = store.loadProperties(ids, fields);

        List<ObjectItem> items = new ArrayList<>(page.size());
        for (Head head : page) {
            items.add(new ObjectItem(head.objectId(), head.typeCode(), head.businessKey(), head.version(),
                    head.stateOwner(), head.title(), project(fields, props.get(head.objectId()))));
        }

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            Head last = page.get(page.size() - 1);
            nextCursor = sort == null
                    ? Cursor.encode(last.objectId(), null, false)
                    : Cursor.encode(last.objectId(), stringify(last.sortValue()), true);
        }
        return PageResult.of(items, nextCursor, hasMore);
    }

    public long count(CountRequest request) {
        if (request == null || request.typeCode() == null || request.typeCode().isBlank()) {
            throw OntologyException.badRequest("typeCode 不能为空");
        }
        ObjectTypeDef type = registry.objectType(request.typeCode());
        authorizer.requireReadType(type.code());
        List<FilterDef> filters = request.filters() == null ? List.of() : request.filters();
        requireFilterPermission(type, filters);
        ObjectQuerySqlBuilder.BuiltQuery query = sqlBuilder.buildCount(type, filters);
        Long n = jdbc.queryForObject(query.sql(), query.params(), Long.class);
        return n == null ? 0 : n;
    }

    public ObjectItem get(String objectId, List<String> requestedFields) {
        ObjectRow row = store.findObject(objectId)
                .orElseThrow(() -> OntologyException.objectNotFound(objectId));
        ObjectTypeDef type = registry.findObjectType(row.typeCode());
        if (type == null) {
            throw OntologyException.objectNotFound(objectId);
        }
        authorizer.requireReadType(type.code());
        List<PropertyDef> fields = resolveFields(type, requestedFields);
        Map<String, Map<String, List<Object>>> props = store.loadProperties(List.of(objectId), fields);
        return new ObjectItem(row.objectId(), row.typeCode(), row.businessKey(), row.rowVersion(),
                row.stateOwner(), row.title(), project(fields, props.get(objectId)));
    }

    // ------------------------------------------------------------------
    private List<PropertyDef> resolveFields(ObjectTypeDef type, List<String> requested) {
        if (requested != null) {
            for (String key : requested) {
                if (key != null && !key.isBlank() && type.find(key) == null) {
                    throw OntologyException.badRequest("未知属性: " + key + "（类型 " + type.code() + "）");
                }
            }
        }
        // 请求 fields 与 ACL 求交集：调用方想拿哪个不能拿哪个
        return authorizer.readableProperties(type, requested);
    }

    private void requireFilterPermission(ObjectTypeDef type, List<FilterDef> filters) {
        for (FilterDef filter : filters) {
            if (filter != null && filter.prop() != null
                    && !authorizer.canReadField(type.code(), filter.prop())) {
                throw OntologyException.forbidden("无权按属性过滤: " + filter.prop());
            }
        }
    }

    private Map<String, Object> project(List<PropertyDef> fields, Map<String, List<Object>> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        for (PropertyDef field : fields) {
            List<Object> v = values.get(field.key());
            if (v == null || v.isEmpty()) {
                continue;
            }
            out.put(field.key(), field.isArray() ? v : v.get(0));
        }
        return out;
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b.toPlainString();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (value instanceof java.time.Instant i) {
            return i.toString();
        }
        if (value instanceof PropertyType) {
            return value.toString();
        }
        return String.valueOf(value);
    }
}
