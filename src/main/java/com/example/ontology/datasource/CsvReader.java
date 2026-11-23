package com.example.ontology.datasource;

import com.example.ontology.common.OntologyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 CSV 读取（RFC4180 风格：支持引号、逗号、引号转义与字段内换行）。
 *
 * <p>没有引入第三方 CSV 库：需求只有"把 CSV 当上游表读进来"，多一个依赖不划算。</p>
 */
public final class CsvReader {

    private CsvReader() {
    }

    public static List<Map<String, Object>> read(Path file, int maxRows) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw OntologyException.badRequest("CSV 读取失败: " + file + " (" + ex.getMessage() + ")");
        }
        List<List<String>> rows = parse(text);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i < rows.size() && out.size() < maxRows; i++) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).isBlank()) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c).trim(), c < row.size() ? row.get(c) : null);
            }
            out.add(map);
        }
        return out;
    }

    private static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // 忽略，等 \n
                }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
