package com.example.ontology.common;

import java.util.List;

/**
 * keyset 分页结果：只有 items / nextCursor / hasMore，不返回 total（计数走单独接口）。
 */
public record PageResult<T>(List<T> items, String nextCursor, boolean hasMore) {

    public static <T> PageResult<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new PageResult<>(items, nextCursor, hasMore);
    }
}
