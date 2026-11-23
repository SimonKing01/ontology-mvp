package com.example.ontology.objectdata;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.example.ontology.support.TestModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询下推（R4）、keyset 分页（禁 OFFSET）、计数与排序。
 */
@ExtendWith(OutputCaptureExtension.class)
class QueryPushdownTest extends BaseIntegrationTest {

    @BeforeEach
    void setUpType() {
        assertThat(api.post("/api/meta/object-types", TestModel.flightType("Flight")).status()).isEqualTo(201);
        seed("F1", "OPEN", 10);
        seed("F2", "OPEN", 20);
        seed("F3", "CLOSED", 30);
        seed("F4", "OPEN", 40);
    }

    private void seed(String key, String status, int delay) {
        seedObject("Flight", key, 0, "source", key);
        seedStringProp("Flight:" + key, "status", status);
        seedNumberProp("Flight:" + key, "delayMinutes", delay);
    }

    @Test
    void 属性过滤翻译成_EXISTS_子查询并出现在_SQL_日志里(CapturedOutput output) {
        TestClient.Resp resp = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight",
                "filters", List.of(Map.of("prop", "status", "op", "eq", "value", "OPEN"))));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.at("items").size()).isEqualTo(3);

        String logs = output.getAll();
        assertThat(logs).contains("对象查询 SQL");
        assertThat(logs).contains("exists (select 1 from obj_prop");
        assertThat(logs).contains("f0.prop_key");
    }

    @Test
    void keyset_分页不重叠且不返回_total() {
        TestClient.Resp first = api.post("/api/objects/query", Map.of("typeCode", "Flight", "pageSize", 2));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.at("items").size()).isEqualTo(2);
        assertThat(first.at("hasMore").asBoolean()).isTrue();
        assertThat(first.body().has("total")).isFalse();
        String cursor = first.str("nextCursor");
        assertThat(cursor).isNotBlank();

        TestClient.Resp second = api.post("/api/objects/query",
                Map.of("typeCode", "Flight", "pageSize", 2, "cursor", cursor));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.at("items").size()).isEqualTo(2);
        assertThat(second.at("hasMore").asBoolean()).isFalse();

        Set<String> ids = new HashSet<>();
        first.at("items").forEach(i -> ids.add(i.path("objectId").asText()));
        second.at("items").forEach(i -> ids.add(i.path("objectId").asText()));
        assertThat(ids).hasSize(4);
    }

    @Test
    void 数值过滤与属性排序都在_SQL_里完成() {
        TestClient.Resp resp = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight",
                "filters", List.of(Map.of("prop", "delayMinutes", "op", "gte", "value", 20)),
                "sort", List.of(Map.of("prop", "delayMinutes", "op", "desc"))));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.at("items").size()).isEqualTo(3);
        List<Integer> delays = new ArrayList<>();
        resp.at("items").forEach(i -> delays.add(i.path("properties").path("delayMinutes").asInt()));
        assertThat(delays).containsExactly(40, 30, 20);
    }

    @Test
    void 计数走独立接口() {
        TestClient.Resp resp = api.post("/api/objects/count", Map.of(
                "typeCode", "Flight",
                "filters", List.of(Map.of("prop", "status", "op", "eq", "value", "OPEN"))));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.at("count").asLong()).isEqualTo(3);
    }

    @Test
    void 未声明属性过滤返回_400() {
        TestClient.Resp resp = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight",
                "filters", List.of(Map.of("prop", "notExist", "op", "eq", "value", "x"))));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("code")).isEqualTo("PARAM_INVALID");
    }
}
