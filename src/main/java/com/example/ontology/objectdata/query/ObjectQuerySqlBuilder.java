package com.example.ontology.objectdata.query;

import com.example.ontology.common.Cursor;
import com.example.ontology.common.OntologyException;
import com.example.ontology.common.PropertyType;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.objectdata.query.ObjectQueryDtos.FilterDef;
import com.example.ontology.objectdata.query.ObjectQueryDtos.SortDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 查询 SQL 构造器 —— R4「查询必须下推为 SQL」的唯一实现点。
 *
 * <p>每个属性过滤都翻译成对 obj_prop 的 EXISTS 子查询（同一个属性多个条件用不同别名），
 * 排序翻译成对 obj_prop 的 LEFT JOIN，分页用 keyset 比较。整个筛选与分页只发一条 SQL。</p>
 *
 * <p>生成的 SQL 会在 DEBUG 级别打印，集成测试据此断言"过滤条件真的进了 SQL"。</p>
 */
@Component
public class ObjectQuerySqlBuilder {

    private static final Logger log = LoggerFactory.getLogger(ObjectQuerySqlBuilder.class);

    private static final Set<String> OPS = Set.of("eq", "in", "gt", "gte", "lt", "lte", "contains", "isnull");
    private static final Set<String> OBJECT_LEVEL_SORTS = Set.of("objectId", "businessKey", "createdAt", "updatedAt");

    public record BuiltQuery(String sql, MapSqlParameterSource params) {
    }

    /**
     * 排序规格。
     *
     * @param expr     排序表达式（o.xxx 或 s.v_xxx）
     * @param joinProp 是否需要 LEFT JOIN obj_prop
     * @param propKey  参与 JOIN 的属性键
     */
    public record SortSpec(String expr, PropertyType type, boolean joinProp, String propKey, boolean desc) {
    }

    public SortSpec resolveSort(ObjectTypeDef type, SortDef sort) {
        if (sort == null || sort.prop() == null || sort.prop().isBlank()) {
            return null;
        }
        boolean desc = "desc".equalsIgnoreCase(sort.op() == null ? "asc" : sort.op().trim());
        if (sort.op() != null && !desc && !"asc".equalsIgnoreCase(sort.op().trim())) {
            throw OntologyException.badRequest("排序方向只支持 asc|desc: " + sort.op());
        }
        String prop = sort.prop().trim();
        return switch (prop) {
            case "objectId" -> new SortSpec("o.object_id", PropertyType.STRING, false, null, desc);
            case "businessKey" -> new SortSpec("o.business_key", PropertyType.STRING, false, null, desc);
            case "createdAt" -> new SortSpec("o.created_at", PropertyType.TIMESTAMP, false, null, desc);
            case "updatedAt" -> new SortSpec("o.updated_at", PropertyType.TIMESTAMP, false, null, desc);
            default -> {
                PropertyDef def = type.find(prop);
                if (def == null) {
                    throw OntologyException.badRequest("排序属性未在 " + type.code() + " 中声明: " + prop);
                }
                if (def.isArray()) {
                    throw OntologyException.badRequest("不支持按多值属性排序: " + prop);
                }
                yield new SortSpec("s." + def.type().column(), def.type(), true, prop, desc);
            }
        };
    }

    public BuiltQuery buildSelect(ObjectTypeDef type, List<FilterDef> filters, SortSpec sort,
                                  Cursor.Token cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                select o.object_id, o.type_code, o.business_key, o.row_version, o.state_owner, o.title, o.stale""");
        if (sort != null) {
            sql.append(", ").append(sort.expr()).append(" as sort_value");
        }
        sql.append(" from obj_object o");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (sort != null && sort.joinProp()) {
            sql.append(" left join obj_prop s on s.object_id = o.object_id and s.prop_key = :sortKey and s.ord = 0");
            params.addValue("sortKey", sort.propKey());
        }
        sql.append(" where o.type_code = :type");
        params.addValue("type", type.code());

        appendFilters(sql, params, type, filters);
        appendKeyset(sql, params, sort, cursor);

        if (sort == null) {
            // object_id 唯一，直接作为 keyset 的排序键
            sql.append(" order by o.object_id asc");
        } else {
            sql.append(" order by ").append(sort.expr())
                    .append(sort.desc() ? " desc" : " asc")
                    .append(" nulls last");
            if (!"o.object_id".equals(sort.expr())) {
                sql.append(", o.object_id asc");
            }
        }
        sql.append(" limit :limit");
        params.addValue("limit", limit);

        log.debug("对象查询 SQL [type={}]: {}", type.code(), sql);
        return new BuiltQuery(sql.toString(), params);
    }

    public BuiltQuery buildCount(ObjectTypeDef type, List<FilterDef> filters) {
        StringBuilder sql = new StringBuilder("select count(*) from obj_object o where o.type_code = :type");
        MapSqlParameterSource params = new MapSqlParameterSource("type", type.code());
        appendFilters(sql, params, type, filters);
        log.debug("对象计数 SQL [type={}]: {}", type.code(), sql);
        return new BuiltQuery(sql.toString(), params);
    }

    // ------------------------------------------------------------------
    private void appendFilters(StringBuilder sql, MapSqlParameterSource params,
                               ObjectTypeDef type, List<FilterDef> filters) {
        if (filters == null) {
            return;
        }
        int index = 0;
        for (FilterDef filter : filters) {
            if (filter == null || filter.prop() == null || filter.prop().isBlank()) {
                throw OntologyException.badRequest("filter.prop 不能为空");
            }
            String op = filter.op() == null ? "eq" : filter.op().trim().toLowerCase(Locale.ROOT);
            if (!OPS.contains(op)) {
                throw OntologyException.badRequest("不支持的过滤算子: " + filter.op()
                        + "（允许：eq|in|gt|gte|lt|lte|contains|isNull）");
            }
            PropertyDef prop = type.find(filter.prop());
            if (prop == null) {
                throw OntologyException.badRequest("过滤属性未在 " + type.code() + " 中声明: " + filter.prop());
            }
            if (prop.isArray()) {
                throw OntologyException.badRequest("不支持在多值属性上过滤: " + filter.prop());
            }
            prop.type().requireScalar(filter.value(), filter.prop());

            String alias = "f" + index++;
            String keyParam = alias + "Key";
            String valParam = alias + "Val";
            params.addValue(keyParam, prop.key());

            if ("isnull".equals(op)) {
                // 属性不存在 == 值为空（obj_prop 不存 null 行）
                sql.append(" and not exists (select 1 from obj_prop ").append(alias)
                        .append(" where ").append(alias).append(".object_id = o.object_id and ")
                        .append(alias).append(".prop_key = :").append(keyParam).append(")");
                continue;
            }

            String column = alias + "." + prop.type().column();
            sql.append(" and exists (select 1 from obj_prop ").append(alias)
                    .append(" where ").append(alias).append(".object_id = o.object_id and ")
                    .append(alias).append(".prop_key = :").append(keyParam);

            switch (op) {
                case "eq" -> {
                    if (filter.value() == null) {
                        throw OntologyException.badRequest("eq 的 value 不能为空，判空请用 isNull: " + filter.prop());
                    }
                    params.addValue(valParam, prop.type().columnValue(filter.value()));
                    sql.append(" and ").append(column).append(" = :").append(valParam);
                }
                case "in" -> {
                    if (!(filter.value() instanceof List<?> list) || list.isEmpty()) {
                        throw OntologyException.badRequest("in 的 value 必须是非空数组: " + filter.prop());
                    }
                    List<Object> values = new ArrayList<>();
                    for (Object v : list) {
                        values.add(prop.type().columnValue(v));
                    }
                    params.addValue(valParam, values);
                    sql.append(" and ").append(column).append(" in (:").append(valParam).append(")");
                }
                case "gt", "gte", "lt", "lte" -> {
                    if (filter.value() == null) {
                        throw OntologyException.badRequest(op + " 的 value 不能为空: " + filter.prop());
                    }
                    if (prop.type() == PropertyType.STRING || prop.type() == PropertyType.BOOLEAN) {
                        throw OntologyException.badRequest(
                                op + " 只支持数值/时间属性，字符串请用 eq/contains: " + filter.prop());
                    }
                    params.addValue(valParam, prop.type().columnValue(filter.value()));
                    String symbol = switch (op) {
                        case "gt" -> " > ";
                        case "gte" -> " >= ";
                        case "lt" -> " < ";
                        default -> " <= ";
                    };
                    sql.append(" and ").append(column).append(symbol).append(":").append(valParam);
                }
                case "contains" -> {
                    if (prop.type() != PropertyType.STRING) {
                        throw OntologyException.badRequest("contains 只支持 string 属性: " + filter.prop());
                    }
                    if (filter.value() == null) {
                        throw OntologyException.badRequest("contains 的 value 不能为空: " + filter.prop());
                    }
                    params.addValue(valParam, "%" + escapeLike(String.valueOf(filter.value())) + "%");
                    sql.append(" and ").append(column).append(" like :").append(valParam)
                            .append(" escape '\\'");
                }
                default -> throw OntologyException.badRequest("不支持的过滤算子: " + op);
            }
            sql.append(")");
        }
    }

    private void appendKeyset(StringBuilder sql, MapSqlParameterSource params, SortSpec sort, Cursor.Token cursor) {
        if (cursor == null) {
            return;
        }
        // 默认排序：keyset 直接比 object_id（契约示例里的裸 "Order:ORD-000123"）
        if (sort == null) {
            sql.append(" and o.object_id > :cursorId");
            params.addValue("cursorId", cursor.objectId());
            return;
        }
        if (cursor.sortValue() == null) {
            sql.append(" and ").append(sort.expr()).append(" is null")
                    .append(" and o.object_id > :cursorId");
            params.addValue("cursorId", cursor.objectId());
            return;
        }
        params.addValue("cursorV", sort.type().columnValue(cursor.sortValue()));
        params.addValue("cursorId", cursor.objectId());
        String cmp = sort.desc() ? " < " : " > ";
        sql.append(" and (").append(sort.expr()).append(cmp).append(":cursorV")
                .append(" or (").append(sort.expr()).append(" = :cursorV and o.object_id > :cursorId)")
                .append(" or ").append(sort.expr()).append(" is null)");
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
