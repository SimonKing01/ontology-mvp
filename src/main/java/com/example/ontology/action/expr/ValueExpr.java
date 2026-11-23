package com.example.ontology.action.expr;

import com.example.ontology.common.OntologyException;

/**
 * 变更值表达式。
 *
 * <p>刻意做成一个极小的、白名单式的表达式集合 —— 绝不允许在 Action 里执行任意代码
 * （不做 Groovy、不做 EL），这是安全边界。</p>
 *
 * <ul>
 *   <li>{@code literal:'CANCELLED'} / {@code literal:100} / {@code literal:true}</li>
 *   <li>{@code param:reason} —— 引用 Action 入参</li>
 *   <li>{@code now()} —— 当前时刻（timestamp 属性用）</li>
 * </ul>
 */
public sealed interface ValueExpr {

    record Literal(String raw) implements ValueExpr {
    }

    record Param(String name) implements ValueExpr {
    }

    record Now() implements ValueExpr {
    }

    static ValueExpr parse(String expr) {
        if (expr == null || expr.isBlank()) {
            throw OntologyException.badRequest("值表达式不能为空");
        }
        String e = expr.trim();
        if ("now()".equalsIgnoreCase(e)) {
            return new Now();
        }
        if (e.startsWith("param:")) {
            String name = e.substring("param:".length()).trim();
            if (!name.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
                throw OntologyException.badRequest("param 引用名不合法: " + name);
            }
            return new Param(name);
        }
        if (e.startsWith("literal:")) {
            String raw = e.substring("literal:".length()).trim();
            if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
                return new Literal(raw.substring(1, raw.length() - 1));
            }
            if (raw.matches("^-?\\d+(\\.\\d+)?$") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                return new Literal(raw);
            }
            throw OntologyException.badRequest(
                    "literal 只支持 '字符串'、数字或布尔值: " + expr);
        }
        throw OntologyException.badRequest(
                "无法解析的值表达式: " + expr + "（支持 literal:'x' / param:x / now()）");
    }

    default String describe() {
        if (this instanceof Literal l) {
            return "literal:" + l.raw();
        }
        if (this instanceof Param p) {
            return "param:" + p.name();
        }
        return "now()";
    }
}
