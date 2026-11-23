package com.example.ontology.common;

import java.util.regex.Pattern;

/**
 * SQL 标识符校验。
 *
 * <p>表名 / 列名来自元模型（可信来源），但依然必须校验：SQL 标识符无法参数化，
 * 这是 R9「所有值参数化」的唯一例外边界。校验不通过直接 400。</p>
 */
public final class SqlIdent {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final int MAX_LENGTH = 63;

    private SqlIdent() {
    }

    public static String require(String ident, String what) {
        if (ident == null || ident.isBlank()) {
            throw OntologyException.badRequest(what + " 不能为空");
        }
        if (ident.length() > MAX_LENGTH || !SAFE.matcher(ident).matches()) {
            throw OntologyException.badRequest("非法标识符(" + what + "): " + ident);
        }
        return ident;
    }

    /** 校验并加双引号，用于拼接 SQL。 */
    public static String quote(String ident, String what) {
        return "\"" + require(ident, what) + "\"";
    }

    /** schema.table，两段都校验。 */
    public static String qualify(String schema, String table) {
        return schema == null || schema.isBlank()
                ? quote(table, "table")
                : quote(schema, "schema") + "." + quote(table, "table");
    }
}
