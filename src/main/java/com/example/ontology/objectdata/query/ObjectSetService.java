package com.example.ontology.objectdata.query;

import com.example.ontology.common.OntologyException;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.objectdata.query.ObjectQueryDtos.FilterDef;
import com.example.ontology.objectdata.query.ObjectQueryDtos.SortDef;
import com.example.ontology.security.OntologyAuthorizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对象集：一次查询的持久化定义。
 *
 * <p>存条件而不是结果——结果会随采集变化，条件不会。所以集合可以被反复使用、
 * 单独授权，也能被别的查询当输入源引用。</p>
 *
 * <p>权限：owner 能读写自己的集合；shared=true 的集合所有人可读，但只有 owner 能删。</p>
 */
@Service
public class ObjectSetService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MetamodelRegistry registry;
    private final OntologyAuthorizer authorizer;

    public ObjectSetService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                            MetamodelRegistry registry, OntologyAuthorizer authorizer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registry = registry;
        this.authorizer = authorizer;
    }

    public record Definition(String id, String name, String typeCode, String owner,
                             boolean shared, Instant createdAt,
                             List<FilterDef> filters, List<SortDef> sort, List<String> fields) {
    }

    public record CreateRequest(String name, String typeCode, List<FilterDef> filters,
                                List<SortDef> sort, List<String> fields, Boolean shared) {
    }

    private static final RowMapper<Map<String, Object>> ROW = (rs, i) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("name", rs.getString("name"));
        m.put("typeCode", rs.getString("type_code"));
        m.put("queryJson", rs.getString("query_json"));
        m.put("owner", rs.getString("owner"));
        m.put("shared", rs.getBoolean("shared"));
        m.put("createdAt", rs.getTimestamp("created_at").toInstant());
        return m;
    };

    public List<Definition> list() {
        String actor = authorizer.currentActor();
        List<Map<String, Object>> rows = jdbc.query("""
                        select id, name, type_code, query_json, owner, shared, created_at
                        from obj_set
                        where owner = :actor or shared
                        order by created_at desc
                        limit 200
                        """,
                new MapSqlParameterSource("actor", actor), ROW);
        List<Definition> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(toDefinition(row));
        }
        return out;
    }

    public Definition create(CreateRequest request) {
        if (request == null || request.typeCode() == null || request.typeCode().isBlank()) {
            throw OntologyException.badRequest("typeCode 不能为空");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw OntologyException.badRequest("集合名称不能为空");
        }
        ObjectTypeDef type = registry.objectType(request.typeCode());
        authorizer.requireReadType(type.code());

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("filters", request.filters() == null ? List.of() : request.filters());
        query.put("sort", request.sort() == null ? List.of() : request.sort());
        query.put("fields", request.fields() == null ? List.of() : request.fields());

        String id = "os_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                        insert into obj_set (id, name, type_code, query_json, owner, shared)
                        values (:id, :name, :type, cast(:query as jsonb), :owner, :shared)
                        """,
                new MapSqlParameterSource("id", id)
                        .addValue("name", request.name().trim())
                        .addValue("type", type.code())
                        .addValue("query", toJson(query))
                        .addValue("owner", authorizer.currentActor())
                        .addValue("shared", Boolean.TRUE.equals(request.shared())));
        return get(id);
    }

    public Definition get(String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                        select id, name, type_code, query_json, owner, shared, created_at
                        from obj_set where id = :id
                        """,
                new MapSqlParameterSource("id", id), ROW);
        if (rows.isEmpty()) {
            throw OntologyException.definitionNotFound("OBJECT_SET", id);
        }
        return toDefinition(rows.get(0));
    }

    public void delete(String id) {
        Definition def = get(id);
        String actor = authorizer.currentActor();
        if (!def.owner().equals(actor)) {
            throw OntologyException.forbidden("只有集合的创建者可以删除: " + id);
        }
        jdbc.update("delete from obj_set where id = :id", new MapSqlParameterSource("id", id));
    }

    /** 查询用：取出集合定义并校验可读 + 类型匹配。 */
    public Definition requireReadable(String id, String expectedTypeCode) {
        Definition def = get(id);
        String actor = authorizer.currentActor();
        if (!def.shared() && !def.owner().equals(actor)) {
            throw OntologyException.forbidden("无权使用该对象集: " + id);
        }
        if (expectedTypeCode != null && !expectedTypeCode.equals(def.typeCode())) {
            throw OntologyException.badRequest("对象集的类型是 " + def.typeCode()
                    + "，与查询的 " + expectedTypeCode + " 不一致");
        }
        authorizer.requireReadType(def.typeCode());
        return def;
    }

    @SuppressWarnings("unchecked")
    private Definition toDefinition(Map<String, Object> row) {
        List<FilterDef> filters = List.of();
        List<SortDef> sort = List.of();
        List<String> fields = List.of();
        try {
            Map<String, Object> query = mapper.readValue(String.valueOf(row.get("queryJson")),
                    new TypeReference<Map<String, Object>>() { });
            Object f = query.get("filters");
            if (f != null) {
                filters = mapper.convertValue(f, new TypeReference<List<FilterDef>>() { });
            }
            Object s = query.get("sort");
            if (s != null) {
                sort = mapper.convertValue(s, new TypeReference<List<SortDef>>() { });
            }
            Object fl = query.get("fields");
            if (fl != null) {
                fields = mapper.convertValue(fl, new TypeReference<List<String>>() { });
            }
        } catch (Exception e) {
            throw OntologyException.badRequest("对象集的查询定义无法解析: " + row.get("id"));
        }
        return new Definition((String) row.get("id"), (String) row.get("name"),
                (String) row.get("typeCode"), (String) row.get("owner"),
                Boolean.TRUE.equals(row.get("shared")), (Instant) row.get("createdAt"),
                filters, sort, fields);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw OntologyException.badRequest("查询定义无法序列化: " + e.getMessage());
        }
    }
}
