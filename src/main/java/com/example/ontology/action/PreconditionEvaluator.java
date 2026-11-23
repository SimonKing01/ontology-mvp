package com.example.ontology.action;

import com.example.ontology.common.OntologyException;
import com.example.ontology.common.PropertyType;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PreconditionDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.objectdata.ObjectRow;
import com.example.ontology.objectdata.ObjectStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 前置条件求值：全部是声明式条件，失败返回 422 + 机器可读 errorCode。
 */
@Component
public class PreconditionEvaluator {

    private final ObjectStore store;

    public PreconditionEvaluator(ObjectStore store) {
        this.store = store;
    }

    public void checkAll(ActionTypeDef action, ObjectTypeDef type, ObjectRow target,
                         Map<String, Object> props, Map<String, Object> params) {
        for (PreconditionDef condition : action.preconditionsOrEmpty()) {
            if (!satisfied(condition, type, target, props, params)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("errorCode", condition.errorCode());
                details.put("precondition", condition.type());
                details.put("objectId", target.objectId());
                if (condition.prop() != null) {
                    details.put("prop", condition.prop());
                    details.put("actual", props.get(condition.prop()));
                    details.put("expected", condition.value());
                }
                details.put("message", condition.message() == null
                        ? "前置条件不满足: " + condition.type()
                        : condition.message());
                throw new OntologyException(com.example.ontology.common.ErrorCode.PRECONDITION_FAILED,
                        condition.message() == null
                                ? "前置条件不满足 [" + condition.errorCode() + "]: " + condition.type()
                                : condition.message(),
                        details);
            }
        }
    }

    private boolean satisfied(PreconditionDef c, ObjectTypeDef type, ObjectRow target,
                              Map<String, Object> props, Map<String, Object> params) {
        String op = c.type() == null ? "" : c.type().trim().toLowerCase(Locale.ROOT);
        Object actual = c.prop() == null ? null : props.get(c.prop());
        return switch (op) {
            case "notnull" -> actual != null;
            case "isnull" -> actual == null;
            case "equals" -> valueEquals(type, c, actual);
            case "notequals" -> !valueEquals(type, c, actual);
            case "in" -> inList(type, c, actual, true);
            case "notin" -> inList(type, c, actual, false);
            case "gt" -> actual != null && compare(type, c, actual) > 0;
            case "gte" -> actual != null && compare(type, c, actual) >= 0;
            case "lt" -> actual != null && compare(type, c, actual) < 0;
            case "lte" -> actual != null && compare(type, c, actual) <= 0;
            case "haslink" -> store.countLinks(c.linkType(), target.objectId(), direction(c)) > 0;
            case "nothaslink" -> store.countLinks(c.linkType(), target.objectId(), direction(c)) == 0;
            case "relatedcountlt" -> {
                String subject = subjectId(c, target.objectId(), params);
                int count = store.countLinksExcluding(c.linkType(), subject, direction(c), target.objectId());
                yield count < ((Number) c.value()).intValue();
            }
            default -> throw OntologyException.badRequest("不支持的前置条件类型: " + c.type());
        };
    }

    private boolean valueEquals(ObjectTypeDef type, PreconditionDef c, Object actual) {
        PropertyDef prop = type.find(c.prop());
        if (prop == null) {
            throw OntologyException.badRequest("前置条件引用了未声明属性: " + c.prop());
        }
        Object expected = prop.type().columnValue(c.value());
        Object normalized = comparable(prop.type(), actual);
        return equalValues(expected, normalized);
    }

    private boolean inList(ObjectTypeDef type, PreconditionDef c, Object actual, boolean expectIn) {
        PropertyDef prop = type.find(c.prop());
        if (prop == null || !(c.value() instanceof List<?> list)) {
            return false;
        }
        Object normalized = comparable(prop.type(), actual);
        boolean found = false;
        for (Object raw : list) {
            if (equalValues(prop.type().columnValue(raw), normalized)) {
                found = true;
                break;
            }
        }
        return expectIn == found;
    }

    private int compare(ObjectTypeDef type, PreconditionDef c, Object actual) {
        PropertyDef prop = type.find(c.prop());
        if (prop == null || actual == null) {
            return -1;
        }
        Object a = comparable(prop.type(), actual);
        Object b = prop.type().columnValue(c.value());
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y);
        }
        if (a instanceof OffsetDateTime x && b instanceof OffsetDateTime y) {
            return x.toInstant().compareTo(y.toInstant());
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        return -1;
    }

    private boolean equalValues(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return Objects.equals(expected, actual);
        }
        if (expected instanceof BigDecimal a && actual instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        if (expected instanceof OffsetDateTime a && actual instanceof OffsetDateTime b) {
            return a.toInstant().equals(b.toInstant());
        }
        return expected.equals(actual);
    }

    /** 把运行时值归一到与 SQL 列一致的可比较域。 */
    private Object comparable(PropertyType type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type.column()) {
            case "v_num" -> {
                if (value instanceof Boolean b) {
                    yield b ? BigDecimal.ONE : BigDecimal.ZERO;
                }
                if (value instanceof BigDecimal b) {
                    yield b;
                }
                if (value instanceof Number n) {
                    yield new BigDecimal(n.toString());
                }
                yield new BigDecimal(String.valueOf(value));
            }
            case "v_str" -> String.valueOf(value);
            default -> {
                if (value instanceof OffsetDateTime odt) {
                    yield odt;
                }
                if (value instanceof Instant i) {
                    yield OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
                }
                if (value instanceof LocalDate d) {
                    yield OffsetDateTime.of(d.atStartOfDay(), ZoneOffset.UTC);
                }
                yield OffsetDateTime.parse(String.valueOf(value));
            }
        };
    }

    private String direction(PreconditionDef c) {
        return c.direction() == null ? "OUT" : c.direction().trim().toUpperCase(Locale.ROOT);
    }

    private String subjectId(PreconditionDef c, String targetId, Map<String, Object> params) {
        String subject = c.subject() == null ? "target" : c.subject().trim();
        if ("target".equals(subject)) {
            return targetId;
        }
        if (subject.startsWith("param:")) {
            Object value = params.get(subject.substring("param:".length()));
            if (value instanceof String s) {
                return s;
            }
            throw OntologyException.badRequest("前置条件 subject 必须是单个 objectRef 参数: " + subject);
        }
        throw OntologyException.badRequest("非法 subject: " + subject);
    }
}
