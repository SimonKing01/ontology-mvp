package com.example.ontology.objectdata.query;

import com.example.ontology.common.OntologyException;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.objectdata.query.ObjectQueryDtos.FilterDef;
import com.example.ontology.security.OntologyAuthorizer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 聚合：按一个属性分组，统计 count / sum / avg / min / max。
 *
 * <p>数据在 {@code obj_prop} 里是 EAV（一行一个属性值），所以每个参与聚合的属性
 * 都要 join 一次 obj_prop：分组属性用 inner join（没有该属性的对象不参与），
 * 度量属性用 left join（缺失当 NULL，聚合函数会忽略）。</p>
 *
 * <p>语义约定：分组属性缺失的对象会落进 {@code null} 组，前端显示为「未填」——
 * "没填"本身是一个有意义的分类，不能静默丢掉。</p>
 */
@Service
public class AggregateService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> METRIC_FNS = Set.of("count", "sum", "avg", "min", "max");

    private final NamedParameterJdbcTemplate jdbc;
    private final MetamodelRegistry registry;
    private final OntologyAuthorizer authorizer;
    private final ObjectQuerySqlBuilder sqlBuilder;

    public AggregateService(NamedParameterJdbcTemplate jdbc, MetamodelRegistry registry,
                            OntologyAuthorizer authorizer, ObjectQuerySqlBuilder sqlBuilder) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.authorizer = authorizer;
        this.sqlBuilder = sqlBuilder;
    }

    public record MetricDef(String fn, String prop) {
    }

    public record Request(String typeCode, List<FilterDef> filters, List<String> groupBy,
                          List<MetricDef> metrics, Integer limit) {
    }

    public record Group(Object key, long count, Map<String, Object> metrics) {
    }

    public record Result(String typeCode, String groupBy, String groupType,
                         int groupCount, boolean truncated, List<Group> groups) {
    }

    public Result aggregate(Request request) {
        if (request == null || request.typeCode() == null || request.typeCode().isBlank()) {
            throw OntologyException.badRequest("typeCode 不能为空");
        }
        ObjectTypeDef type = registry.objectType(request.typeCode());
        authorizer.requireReadType(type.code());

        List<String> groupBy = request.groupBy() == null ? List.of() : request.groupBy();
        if (groupBy.size() != 1) {
            throw OntologyException.badRequest("MVP 只支持按一个属性分组，收到 " + groupBy.size() + " 个");
        }
        String groupKey = groupBy.get(0);
        PropertyDef groupProp = type.require(groupKey);
        if (groupProp.isArray()) {
            throw OntologyException.badRequest("多值属性不能用于分组（同一个对象会出现在多组里）: " + groupKey);
        }
        if (!authorizer.canReadField(type.code(), groupKey)) {
            throw OntologyException.forbidden("无权按属性分组: " + groupKey);
        }

        List<MetricDef> metrics = request.metrics() == null || request.metrics().isEmpty()
                ? List.of(new MetricDef("count", null)) : request.metrics();

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("type", type.code());
        params.addValue("gprop", groupKey);

        String groupExpr = columnFor(groupProp) + "::text";
        StringBuilder select = new StringBuilder("select ").append(groupExpr).append(" as gkey, count(*) as cnt");
        StringBuilder joins = new StringBuilder();
        List<String> labels = new ArrayList<>();
        List<Integer> numericIndexes = new ArrayList<>();

        int idx = 0;
        for (MetricDef metric : metrics) {
            String fn = metric.fn() == null ? "count" : metric.fn().toLowerCase(Locale.ROOT);
            if (!METRIC_FNS.contains(fn)) {
                throw OntologyException.badRequest("不支持的聚合函数: " + metric.fn()
                        + "（允许 " + String.join("/", METRIC_FNS) + "）");
            }
            if ("count".equals(fn) && (metric.prop() == null || metric.prop().isBlank())) {
                labels.add("count");
                numericIndexes.add(-1);
                continue;
            }
            if (metric.prop() == null || metric.prop().isBlank()) {
                throw OntologyException.badRequest(fn + " 必须指定 prop");
            }
            PropertyDef prop = type.require(metric.prop());
            if (prop.isArray()) {
                throw OntologyException.badRequest("多值属性不能参与聚合: " + metric.prop());
            }
            if (!authorizer.canReadField(type.code(), metric.prop())) {
                throw OntologyException.forbidden("无权聚合属性: " + metric.prop());
            }
            if (!prop.type().numericColumn()) {
                throw OntologyException.badRequest(fn + " 只支持数值属性，" + metric.prop()
                        + " 是 " + prop.type().json());
            }
            select.append(", ").append(fn).append("(m").append(idx).append(".v_num) as m").append(idx);
            joins.append("\n                left join obj_prop m").append(idx)
                    .append(" on m").append(idx).append(".object_id = o.object_id and m")
                    .append(idx).append(".prop_key = :p").append(idx)
                    .append(" and m").append(idx).append(".ord = 0");
            params.addValue("p" + idx, metric.prop());
            labels.add(fn + "_" + metric.prop());
            numericIndexes.add(idx);
            idx++;
        }

        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        if (limit < 1) {
            throw OntologyException.badRequest("limit 必须大于 0");
        }
        limit = Math.min(limit, MAX_LIMIT);

        StringBuilder sql = new StringBuilder(select)
                .append("\n                from obj_object o")
                .append("\n                left join obj_prop g on g.object_id = o.object_id and g.prop_key = :gprop and g.ord = 0")
                .append(joins)
                .append("\n                where o.type_code = :type");
        sqlBuilder.appendFilters(sql, params, type, request.filters() == null ? List.of() : request.filters());
        sql.append("\n                group by gkey\n                order by cnt desc, gkey asc\n                limit :limit");
        params.addValue("limit", limit + 1);

        List<Group> rows = jdbc.query(sql.toString(), params, (rs, i) -> {
            Object key = rs.getObject("gkey");
            Map<String, Object> values = new LinkedHashMap<>();
            for (int k = 0; k < labels.size(); k++) {
                int col = numericIndexes.get(k);
                values.put(labels.get(k), col < 0 ? rs.getLong("cnt") : rs.getObject("m" + col));
            }
            return new Group(key, rs.getLong("cnt"), values);
        });

        boolean truncated = rows.size() > limit;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new Result(type.code(), groupKey, groupProp.type().json(), rows.size(), truncated, rows);
    }

    private String columnFor(PropertyDef prop) {
        if (prop.type().numericColumn()) {
            return "g.v_num";
        }
        return switch (prop.type()) {
            case DATE, TIMESTAMP -> "g.v_ts";
            default -> "g.v_str";
        };
    }
}
