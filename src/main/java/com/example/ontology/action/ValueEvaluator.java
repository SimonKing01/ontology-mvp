package com.example.ontology.action;

import com.example.ontology.common.OntologyException;
import com.example.ontology.common.PropertyType;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.ActionParamDef;
import com.example.ontology.meta.model.PropertyDef;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 值表达式求值器。
 *
 * <p>只支持 literal / param / now() 三种，全部走白名单；不执行任何用户代码。</p>
 */
@Component
public class ValueEvaluator {

    public Object evaluate(String expression, Map<String, Object> params, PropertyDef prop) {
        ValueExprHolder parsed = new ValueExprHolder(expression);
        return switch (parsed.kind) {
            case "now" -> {
                if (prop.type() == PropertyType.TIMESTAMP) {
                    yield java.time.Instant.now();
                }
                if (prop.type() == PropertyType.DATE) {
                    yield java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                }
                throw OntologyException.badRequest("now() 只能用于 date/timestamp 属性: " + prop.key());
            }
            case "param" -> {
                Object raw = params.get(parsed.argument);
                if (raw == null) {
                    throw OntologyException.badRequest("参数为空，无法写入属性 " + prop.key() + ": param:" + parsed.argument);
                }
                if (raw instanceof Collection<?> || raw instanceof Map<?, ?>) {
                    throw OntologyException.badRequest(
                            "属性 " + prop.key() + " 是单值，不能使用多值参数: param:" + parsed.argument);
                }
                Object v = prop.type().coerce(raw);
                if (v == null) {
                    throw OntologyException.badRequest(
                            "参数 param:" + parsed.argument + " 无法转换为 " + prop.type().json());
                }
                yield v;
            }
            default -> {
                Object v = prop.type().coerce(parsed.argument);
                if (v == null) {
                    throw OntologyException.badRequest(
                            "字面量 " + parsed.argument + " 无法转换为属性 " + prop.key() + " 的 " + prop.type().json());
                }
                yield v;
            }
        };
    }

    /** 求值成字符串（用于 businessKeyExpr / titleExpr / createLink 的 to）。 */
    public String evaluateAsString(String expression, Map<String, Object> params) {
        ValueExprHolder parsed = new ValueExprHolder(expression);
        return switch (parsed.kind) {
            case "now" -> java.time.Instant.now().toString();
            case "param" -> {
                Object raw = params.get(parsed.argument);
                if (raw == null) {
                    throw OntologyException.badRequest("参数为空: param:" + parsed.argument);
                }
                if (raw instanceof Collection<?> || raw instanceof Map<?, ?>) {
                    throw OntologyException.badRequest("该表达式需要单值参数: param:" + parsed.argument);
                }
                yield String.valueOf(raw);
            }
            default -> parsed.argument;
        };
    }

    /**
     * 校验 Action 参数（类型、必填、长度）。返回规范化后的参数表。
     */
    public Map<String, Object> validateParams(ActionTypeDef action, Map<String, Object> rawParams) {
        Map<String, Object> params = rawParams == null ? Map.of() : rawParams;
        for (ActionParamDef def : action.paramsOrEmpty()) {
            Object value = params.get(def.name());
            if (value == null) {
                if (def.required()) {
                    throw OntologyException.badRequest("缺少必填参数: " + def.name(),
                            Map.of("param", def.name(), "action", action.code()));
                }
                continue;
            }
            if (def.isObjectRef()) {
                validateObjectRefValue(def, value);
            } else {
                validateScalar(def, value);
            }
        }
        return params;
    }

    private void validateObjectRefValue(ActionParamDef def, Object value) {
        if (def.array()) {
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw OntologyException.badRequest("参数 " + def.name() + " 必须是非空 objectRef 数组");
            }
            for (Object item : list) {
                if (!(item instanceof String s) || s.isBlank()) {
                    throw OntologyException.badRequest("参数 " + def.name() + " 的元素必须是 objectId 字符串");
                }
                requireRefType(def, s);
            }
        } else {
            if (!(value instanceof String s) || s.isBlank()) {
                throw OntologyException.badRequest("参数 " + def.name() + " 必须是 objectId 字符串");
            }
            requireRefType(def, s);
        }
    }

    private void requireRefType(ActionParamDef def, String objectId) {
        String type = objectId.contains(":") ? objectId.substring(0, objectId.indexOf(':')) : "";
        if (!type.equals(def.refType())) {
            throw OntologyException.badRequest("参数 " + def.name() + " 必须是 " + def.refType() + " 的对象引用，收到: " + objectId);
        }
    }

    private void validateScalar(ActionParamDef def, Object value) {
        PropertyType type = PropertyType.from(def.type().toLowerCase(Locale.ROOT));
        if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
            throw OntologyException.badRequest("参数 " + def.name() + " 必须是标量");
        }
        if (def.maxLength() != null && value instanceof String s && s.length() > def.maxLength()) {
            throw OntologyException.badRequest("参数 " + def.name() + " 长度超过上限 " + def.maxLength());
        }
        if (type.coerce(value) == null) {
            throw OntologyException.badRequest(
                    "参数 " + def.name() + " 类型不匹配，期望 " + def.type());
        }
    }

    /** 轻量解析结果（避免把 ValueExpr 的解析细节散落到引擎里）。 */
    private static final class ValueExprHolder {
        private final String kind;
        private final String argument;

        private ValueExprHolder(String expression) {
            if (expression == null || expression.isBlank()) {
                throw OntologyException.badRequest("值表达式不能为空");
            }
            String e = expression.trim();
            if ("now()".equalsIgnoreCase(e)) {
                this.kind = "now";
                this.argument = null;
            } else if (e.startsWith("param:")) {
                this.kind = "param";
                this.argument = e.substring("param:".length()).trim();
            } else if (e.startsWith("literal:")) {
                String raw = e.substring("literal:".length()).trim();
                this.kind = "literal";
                this.argument = raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")
                        ? raw.substring(1, raw.length() - 1) : raw;
            } else {
                throw OntologyException.badRequest(
                        "无法解析的值表达式: " + expression + "（支持 literal:'x' / param:x / now()）");
            }
        }
    }
}
