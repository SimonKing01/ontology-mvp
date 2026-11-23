package com.example.ontology.objectdata;

import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.meta.model.PropertyOwner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对象层的唯一数据访问入口（JdbcTemplate，参数全部具名绑定）。
 *
 * <p>它只碰 obj_object / obj_prop / obj_link 三张表，不会把属性序列化成大 JSON，
 * 也不提供"直接改对象"的业务方法：写状态必须由 Action 引擎调用本类的原语完成。</p>
 */
@Component
public class ObjectStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ObjectStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 对象头
    // ------------------------------------------------------------------
    public Optional<ObjectRow> findObject(String objectId) {
        List<ObjectRow> rows = jdbc.query("""
                        select object_id, type_code, business_key, row_version, state_owner, title, created_at, updated_at
                        from obj_object where object_id = :id
                        """,
                Map.of("id", objectId), (rs, i) -> new ObjectRow(
                        rs.getString("object_id"), rs.getString("type_code"), rs.getString("business_key"),
                        rs.getLong("row_version"), rs.getString("state_owner"), rs.getString("title"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
        return rows.stream().findFirst();
    }

    /** 批量取对象头（不加锁，用于链接遍历等只读场景）。 */
    public List<ObjectRow> findObjects(Collection<String> objectIds) {
        if (objectIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        select object_id, type_code, business_key, row_version, state_owner, title, created_at, updated_at
                        from obj_object where object_id in (:ids)
                        """,
                new MapSqlParameterSource("ids", objectIds), (rs, i) -> new ObjectRow(
                        rs.getString("object_id"), rs.getString("type_code"), rs.getString("business_key"),
                        rs.getLong("row_version"), rs.getString("state_owner"), rs.getString("title"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    /** 按 object_id 升序加行锁，避免批量 Action 之间死锁。 */
    public List<ObjectRow> lockObjects(Collection<String> objectIds) {
        if (objectIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        select object_id, type_code, business_key, row_version, state_owner, title, created_at, updated_at
                        from obj_object where object_id in (:ids) order by object_id for update
                        """,
                new MapSqlParameterSource("ids", objectIds), (rs, i) -> new ObjectRow(
                        rs.getString("object_id"), rs.getString("type_code"), rs.getString("business_key"),
                        rs.getLong("row_version"), rs.getString("state_owner"), rs.getString("title"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    /**
     * 采集写入对象头：已存在则只更新 title（保留 row_version 与 state_owner）。
     *
     * @return true 表示新建
     */
    public boolean upsertIngestedObject(String objectId, String typeCode, String businessKey, String title) {
        List<Boolean> inserted = jdbc.query("""
                        insert into obj_object (object_id, type_code, business_key, row_version, state_owner, title, created_at, updated_at)
                        values (:id, :type, :bk, 0, 'source', :title, now(), now())
                        on conflict (object_id) do update set title = excluded.title, updated_at = now()
                        returning (xmax = 0) as inserted
                        """,
                new MapSqlParameterSource()
                        .addValue("id", objectId)
                        .addValue("type", typeCode)
                        .addValue("bk", businessKey)
                        .addValue("title", title, Types.VARCHAR),
                (rs, i) -> rs.getBoolean("inserted"));
        return !inserted.isEmpty() && inserted.get(0);
    }

    /** Action 创建对象（state_owner=ontology：这条记录由本体产生，不来自上游）。 */
    public void insertActionCreatedObject(String objectId, String typeCode, String businessKey, String title) {
        jdbc.update("""
                        insert into obj_object (object_id, type_code, business_key, row_version, state_owner, title, created_at, updated_at)
                        values (:id, :type, :bk, 0, 'ontology', :title, now(), now())
                        """,
                new MapSqlParameterSource()
                        .addValue("id", objectId)
                        .addValue("type", typeCode)
                        .addValue("bk", businessKey)
                        .addValue("title", title, Types.VARCHAR));
    }

    /** 乐观锁 +1，返回新版本号。 */
    public long bumpVersion(String objectId) {
        List<Long> v = jdbc.query("""
                        update obj_object set row_version = row_version + 1, updated_at = now()
                        where object_id = :id returning row_version
                        """,
                Map.of("id", objectId), (rs, i) -> rs.getLong("row_version"));
        return v.isEmpty() ? -1 : v.get(0);
    }

    public boolean exists(String objectId) {
        Integer n = jdbc.queryForObject("select count(*) from obj_object where object_id = :id",
                Map.of("id", objectId), Integer.class);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------
    // 属性
    // ------------------------------------------------------------------
    /**
     * 读取一批对象的属性（只读投影）。
     *
     * @return objectId -> propKey -> 值列表（多值属性按 ord 排序；单值属性列表长度为 1）
     */
    public Map<String, Map<String, List<Object>>> loadProperties(Collection<String> objectIds,
                                                                 Collection<PropertyDef> props) {
        if (objectIds.isEmpty() || props.isEmpty()) {
            return Map.of();
        }
        Map<String, PropertyDef> byKey = new LinkedHashMap<>();
        props.forEach(p -> byKey.put(p.key(), p));

        Map<String, Map<String, List<Object>>> result = new LinkedHashMap<>();
        jdbc.query("""
                        select object_id, prop_key, ord, v_num, v_str, v_ts
                        from obj_prop
                        where object_id in (:ids) and prop_key in (:keys)
                        order by object_id, prop_key, ord
                        """,
                new MapSqlParameterSource()
                        .addValue("ids", objectIds)
                        .addValue("keys", byKey.keySet()),
                rs -> {
                    PropertyDef def = byKey.get(rs.getString("prop_key"));
                    if (def == null) {
                        return;
                    }
                    Object value = def.type().fromColumns(
                            rs.getBigDecimal("v_num"),
                            rs.getString("v_str"),
                            rs.getObject("v_ts", OffsetDateTime.class));
                    if (value == null) {
                        return;
                    }
                    result.computeIfAbsent(rs.getString("object_id"), k -> new LinkedHashMap<>())
                            .computeIfAbsent(def.key(), k -> new ArrayList<>())
                            .add(value);
                });
        return result;
    }

    public Map<String, Map<String, List<Object>>> loadProperties(Collection<String> objectIds, ObjectTypeDef type) {
        return loadProperties(objectIds, type.propertiesOrEmpty());
    }

    /** 写一个属性值（单值 ord=0；多值由调用方递增 ord）。 */
    public void setProperty(String objectId, PropertyDef prop, int ord, Object value) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("oid", objectId)
                .addValue("key", prop.key())
                .addValue("ord", ord);
        switch (prop.type().column()) {
            case "v_num" -> params.addValue("num", toNumber(value), Types.NUMERIC)
                    .addValue("str", null, Types.VARCHAR)
                    .addValue("ts", null, Types.TIMESTAMP_WITH_TIMEZONE);
            case "v_str" -> params.addValue("num", null, Types.NUMERIC)
                    .addValue("str", value == null ? null : String.valueOf(value), Types.VARCHAR)
                    .addValue("ts", null, Types.TIMESTAMP_WITH_TIMEZONE);
            default -> params.addValue("num", null, Types.NUMERIC)
                    .addValue("str", null, Types.VARCHAR)
                    .addValue("ts", toOffsetDateTime(value), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        jdbc.update("""
                        insert into obj_prop (object_id, prop_key, ord, v_num, v_str, v_ts)
                        values (:oid, :key, :ord, :num, :str, :ts)
                        on conflict (object_id, prop_key, ord) do update
                        set v_num = excluded.v_num, v_str = excluded.v_str, v_ts = excluded.v_ts
                        """, params);
    }

    public void clearProperty(String objectId, String propKey) {
        jdbc.update("delete from obj_prop where object_id = :oid and prop_key = :key",
                Map.of("oid", objectId, "key", propKey));
    }

    public void clearPropertyOrd(String objectId, String propKey, int ord) {
        jdbc.update("delete from obj_prop where object_id = :oid and prop_key = :key and ord = :ord",
                new MapSqlParameterSource("oid", objectId).addValue("key", propKey).addValue("ord", ord));
    }

    // ------------------------------------------------------------------
    // 链接
    // ------------------------------------------------------------------
    public void insertLink(String linkType, String fromObjectId, String toObjectId, Map<String, Object> properties) {
        String linkId = linkType + ":" + businessKeyOf(fromObjectId) + "->" + businessKeyOf(toObjectId);
        jdbc.update("""
                        insert into obj_link (link_id, link_type, from_object_id, to_object_id, properties)
                        values (:lid, :lt, :from, :to, cast(:props as jsonb))
                        on conflict (link_type, from_object_id, to_object_id) do update
                        set properties = excluded.properties
                        """,
                new MapSqlParameterSource()
                        .addValue("lid", linkId)
                        .addValue("lt", linkType)
                        .addValue("from", fromObjectId)
                        .addValue("to", toObjectId)
                        .addValue("props", toJson(properties), Types.VARCHAR));
    }

    public void deleteLink(String linkType, String fromObjectId, String toObjectId) {
        jdbc.update("""
                        delete from obj_link where link_type = :lt and from_object_id = :from and to_object_id = :to
                        """,
                new MapSqlParameterSource("lt", linkType).addValue("from", fromObjectId).addValue("to", toObjectId));
    }

    public void deleteLinksOfType(String linkType, String fromObjectId) {
        jdbc.update("delete from obj_link where link_type = :lt and from_object_id = :from",
                new MapSqlParameterSource("lt", linkType).addValue("from", fromObjectId));
    }

    public void deleteIncomingLinksOfType(String linkType, String toObjectId) {
        jdbc.update("delete from obj_link where link_type = :lt and to_object_id = :to",
                new MapSqlParameterSource("lt", linkType).addValue("to", toObjectId));
    }

    public boolean linkExists(String linkType, String fromObjectId, String toObjectId) {
        Integer n = jdbc.queryForObject("""
                        select count(*) from obj_link
                        where link_type = :lt and from_object_id = :from and to_object_id = :to
                        """,
                new MapSqlParameterSource("lt", linkType).addValue("from", fromObjectId).addValue("to", toObjectId),
                Integer.class);
        return n != null && n > 0;
    }

    /** 统计某对象在指定方向上的链接数（不含自身到自身的重复计数）。 */
    public int countLinks(String linkType, String objectId, String direction) {
        return countLinksExcluding(linkType, objectId, direction, null);
    }

    /**
     * 统计链接数，可排除一个对端（用于"这架飞机是否已被别的航班占用"这类前置条件）。
     */
    public int countLinksExcluding(String linkType, String objectId, String direction, String excludeObjectId) {
        boolean in = "IN".equalsIgnoreCase(direction);
        String column = in ? "to_object_id" : "from_object_id";
        String otherColumn = in ? "from_object_id" : "to_object_id";
        StringBuilder sql = new StringBuilder("select count(*) from obj_link where link_type = :lt and ")
                .append(column).append(" = :oid");
        MapSqlParameterSource params = new MapSqlParameterSource("lt", linkType).addValue("oid", objectId);
        if (excludeObjectId != null) {
            sql.append(" and ").append(otherColumn).append(" <> :exclude");
            params.addValue("exclude", excludeObjectId);
        }
        Integer n = jdbc.queryForObject(sql.toString(), params, Integer.class);
        return n == null ? 0 : n;
    }

    /** 一跳遍历（keyset：link_id > cursor）。 */
    public List<LinkRow> findLinks(String objectId, String linkType, String direction, int limit, String cursor) {
        boolean out = !"IN".equalsIgnoreCase(direction);
        String column = out ? "from_object_id" : "to_object_id";
        StringBuilder sql = new StringBuilder(
                "select link_id, link_type, from_object_id, to_object_id from obj_link where ");
        sql.append(column).append(" = :oid");
        MapSqlParameterSource params = new MapSqlParameterSource("oid", objectId).addValue("limit", limit);
        if (linkType != null && !linkType.isBlank()) {
            sql.append(" and link_type = :lt");
            params.addValue("lt", linkType);
        }
        if (cursor != null && !cursor.isBlank()) {
            sql.append(" and link_id > :cursor");
            params.addValue("cursor", cursor);
        }
        sql.append(" order by link_id asc limit :limit");
        return jdbc.query(sql.toString(), params, (rs, i) -> new LinkRow(
                rs.getString("link_id"), rs.getString("link_type"),
                rs.getString("from_object_id"), rs.getString("to_object_id")));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------
    public static String businessKeyOf(String objectId) {
        int idx = objectId.indexOf(':');
        return idx < 0 ? objectId : objectId.substring(idx + 1);
    }

    public static String typeOf(String objectId) {
        int idx = objectId.indexOf(':');
        return idx < 0 ? "" : objectId.substring(0, idx);
    }

    private BigDecimal toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Boolean bool) {
            return bool ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof Instant i) {
            return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
        }
        if (value instanceof LocalDate d) {
            return OffsetDateTime.of(d.atStartOfDay(), ZoneOffset.UTC);
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        return null;
    }

    private String toJson(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(properties);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("链接属性序列化失败", ex);
        }
    }

    /** 供采集判断某个属性是否可写（source / derived 才由采集写）。 */
    public static boolean writableByIngest(PropertyDef p) {
        return p.owner() == PropertyOwner.SOURCE || p.owner() == PropertyOwner.DERIVED;
    }
}
