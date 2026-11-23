package com.example.ontology.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 属性类型与 obj_prop 三列（v_num / v_str / v_ts）的映射规则。
 *
 * <p>这张映射表是整个查询下推的基础：过滤条件必须翻译成对应列上的 SQL 条件，
 * 才能在数据库里完成过滤，而不是查回来再在内存里筛。</p>
 */
public enum PropertyType {

    STRING("string", "v_str"),
    INTEGER("integer", "v_num"),
    LONG("long", "v_num"),
    DECIMAL("decimal", "v_num"),
    BOOLEAN("boolean", "v_num"),
    DATE("date", "v_ts"),
    TIMESTAMP("timestamp", "v_ts");

    private final String json;
    private final String column;

    PropertyType(String json, String column) {
        this.json = json;
        this.column = column;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static PropertyType from(String raw) {
        if (raw == null) {
            throw OntologyException.badRequest("属性 type 不能为空");
        }
        for (PropertyType t : values()) {
            if (t.json.equalsIgnoreCase(raw.trim())) {
                return t;
            }
        }
        throw OntologyException.badRequest("不支持的属性 type: " + raw
                + "（允许值：string|integer|long|decimal|boolean|date|timestamp）");
    }

    /** 该类型存放在 obj_prop 的哪一列 */
    public String column() {
        return column;
    }

    public boolean numericColumn() {
        return "v_num".equals(column);
    }

    /**
     * 把源数据（ResultSet 取出的原始对象）转换成该类型的 Java 值。
     *
     * @return 转换后的值；无法表达时返回 null（宁可留空，也不塞错值）
     */
    public Object coerce(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (this) {
                case STRING -> raw instanceof String s ? s
                        : (raw instanceof Number || raw instanceof Boolean) ? String.valueOf(raw) : null;
                case INTEGER -> raw instanceof Number n ? n.intValue()
                        : Integer.valueOf(String.valueOf(raw).trim());
                case LONG -> raw instanceof Number n ? n.longValue()
                        : Long.valueOf(String.valueOf(raw).trim());
                case DECIMAL -> raw instanceof BigDecimal b ? b
                        : raw instanceof Number n ? new BigDecimal(n.toString())
                        : new BigDecimal(String.valueOf(raw).trim());
                case BOOLEAN -> raw instanceof Boolean b ? b
                        : raw instanceof Number n ? n.intValue() != 0
                        : switch (String.valueOf(raw).trim().toUpperCase()) {
                            case "TRUE", "1", "Y", "YES" -> Boolean.TRUE;
                            case "FALSE", "0", "N", "NO" -> Boolean.FALSE;
                            default -> null;
                        };
                case DATE -> raw instanceof LocalDate d ? d
                        : raw instanceof java.sql.Date sd ? sd.toLocalDate()
                        : raw instanceof LocalDateTime ldt ? ldt.toLocalDate()
                        : raw instanceof Instant i ? LocalDate.ofInstant(i, ZoneOffset.UTC)
                        : LocalDate.parse(String.valueOf(raw).trim().substring(0, 10));
                case TIMESTAMP -> raw instanceof Instant i ? i
                        : raw instanceof OffsetDateTime odt ? odt.toInstant()
                        : raw instanceof Timestamp ts ? ts.toInstant()
                        : raw instanceof java.util.Date d ? d.toInstant()
                        : raw instanceof LocalDateTime ldt ? ldt.toInstant(ZoneOffset.UTC)
                        : Instant.parse(String.valueOf(raw).trim());
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 把 API 请求里的 JSON 值转换成可直接作为 SQL 参数的值。
     * 所有比较都必须走这里，保证"值参数化 + 类型正确"。
     */
    public Object columnValue(Object jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        if (this == STRING) {
            return String.valueOf(jsonValue);
        }
        if (this == BOOLEAN) {
            Object v = coerce(jsonValue);
            if (v == null) {
                throw OntologyException.badRequest("无法解析为 boolean: " + jsonValue);
            }
            return Boolean.TRUE.equals(v) ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (numericColumn()) {
            if (jsonValue instanceof BigDecimal b) {
                return b;
            }
            if (jsonValue instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            try {
                return new BigDecimal(String.valueOf(jsonValue).trim());
            } catch (NumberFormatException ex) {
                throw OntologyException.badRequest("无法解析为数值: " + jsonValue);
            }
        }
        // v_ts 列
        Object coerced = coerce(jsonValue);
        if (coerced instanceof Instant i) {
            return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
        }
        if (coerced instanceof LocalDate d) {
            return OffsetDateTime.of(d.atStartOfDay(), ZoneOffset.UTC);
        }
        throw OntologyException.badRequest("无法解析为时间: " + jsonValue);
    }

    /** 从 obj_prop 的三列还原成 JSON 友好的值。 */
    public Object fromColumns(BigDecimal num, String str, OffsetDateTime ts) {
        return switch (this) {
            case STRING -> str;
            case INTEGER -> num == null ? null : num.intValue();
            case LONG -> num == null ? null : num.longValue();
            case DECIMAL -> num;
            case BOOLEAN -> num == null ? null : num.intValue() != 0;
            case DATE -> ts == null ? null : ts.toLocalDate();
            case TIMESTAMP -> ts == null ? null : ts.toInstant();
        };
    }

    /** 该类型的 JSON 值是否可以出现在过滤条件里（拒绝对象/数组）。 */
    public void requireScalar(Object jsonValue, String propKey) {
        if (jsonValue instanceof Map<?, ?> || jsonValue instanceof List<?>) {
            throw OntologyException.badRequest("属性 " + propKey + " 的过滤值必须是标量");
        }
    }
}
