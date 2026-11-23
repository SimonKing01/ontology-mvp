package com.example.ontology.meta;

/**
 * 元模型种类。kind 存库，path 用于 REST 路由。
 */
public enum DefinitionKind {

    OBJECT_TYPE("OBJECT_TYPE", "object-types"),
    LINK_TYPE("LINK_TYPE", "link-types"),
    ACTION_TYPE("ACTION_TYPE", "action-types");

    private final String dbValue;
    private final String path;

    DefinitionKind(String dbValue, String path) {
        this.dbValue = dbValue;
        this.path = path;
    }

    public String dbValue() {
        return dbValue;
    }

    public String path() {
        return path;
    }

    public static DefinitionKind fromPath(String path) {
        for (DefinitionKind k : values()) {
            if (k.path.equals(path)) {
                return k;
            }
        }
        throw new IllegalArgumentException("未知元模型种类: " + path);
    }
}
