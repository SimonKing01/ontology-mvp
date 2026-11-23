package com.example.ontology.objectdata;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.example.ontology.support.TestModel;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合：按一个属性分组统计。
 *
 * <p>演示数据里 departure 的分布：
 * PEK=2（MU5101 延误 35 / CA1501 延误 20）、CAN=2、PVG=1、CTU=1。</p>
 */
class AggregateTest extends BaseIntegrationTest {

    @Test
    void 按出发机场分组统计数量与平均延误() {
        prepareWithDelayMetric();

        TestClient.Resp resp = api.post("/api/objects/aggregate", Map.of(
                "typeCode", "Flight",
                "groupBy", List.of("departure"),
                "metrics", List.of(
                        Map.of("fn", "count"),
                        Map.of("fn", "avg", "prop", "delayMinutes"),
                        Map.of("fn", "max", "prop", "delayMinutes"))));
        assertThat(resp.status()).isEqualTo(200);

        JsonNode body = resp.body();
        assertThat(body.get("groupBy").asText()).isEqualTo("departure");
        assertThat(body.get("groupCount").asInt()).isEqualTo(4);
        assertThat(body.get("truncated").asBoolean()).isFalse();

        JsonNode pek = findByKey(body.get("groups"), "PEK");
        assertThat(pek).isNotNull();
        assertThat(pek.get("count").asInt()).isEqualTo(2);
        assertThat(pek.get("metrics").get("avg_delayMinutes").decimalValue())
                .isEqualByComparingTo("27.5");
        assertThat(pek.get("metrics").get("max_delayMinutes").decimalValue())
                .isEqualByComparingTo("35");

        // PVG 只有一个航班且没有延误 → avg 为 null，但分组本身要在
        JsonNode pvg = findByKey(body.get("groups"), "PVG");
        assertThat(pvg).isNotNull();
        assertThat(pvg.get("count").asInt()).isEqualTo(1);
        // 全局配置 default-property-inclusion=non_null，null 度量会被省略
        JsonNode pvgAvg = pvg.get("metrics").get("avg_delayMinutes");
        assertThat(pvgAvg == null || pvgAvg.isNull()).isTrue();
    }

    @Test
    void 聚合可以带过滤条件() {
        prepareWithDelayMetric();

        TestClient.Resp resp = api.post("/api/objects/aggregate", Map.of(
                "typeCode", "Flight",
                "filters", List.of(Map.of("prop", "departure", "op", "eq", "value", "PEK")),
                "groupBy", List.of("departure"),
                "metrics", List.of(Map.of("fn", "count"))));
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("groupCount").asInt()).isEqualTo(1);
        assertThat(resp.body().get("groups").get(0).get("count").asInt()).isEqualTo(2);
    }

    @Test
    void 对字符串属性做_avg_返回400() {
        prepareWithDelayMetric();
        TestClient.Resp resp = api.post("/api/objects/aggregate", Map.of(
                "typeCode", "Flight",
                "groupBy", List.of("departure"),
                "metrics", List.of(Map.of("fn", "avg", "prop", "flightNo"))));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("只支持数值属性");
    }

    @Test
    void 多字段分组返回400() {
        prepareWithDelayMetric();
        TestClient.Resp resp = api.post("/api/objects/aggregate", Map.of(
                "typeCode", "Flight",
                "groupBy", List.of("departure", "arrival"),
                "metrics", List.of(Map.of("fn", "count"))));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("只支持按一个属性分组");
    }

    @Test
    void 不存在的分组属性返回400() {
        prepareWithDelayMetric();
        TestClient.Resp resp = api.post("/api/objects/aggregate", Map.of(
                "typeCode", "Flight",
                "groupBy", List.of("noSuchProp"),
                "metrics", List.of(Map.of("fn", "count"))));
        assertThat(resp.status()).isEqualTo(400);
    }

    private JsonNode findByKey(JsonNode groups, String key) {
        for (JsonNode g : groups) {
            if (key.equals(g.get("key").asText())) {
                return g;
            }
        }
        return null;
    }

    private void prepareWithDelayMetric() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();
        // 派生属性依赖 DelayedBy，所以必须建完链接再 PUT（revision -> 2）
        assertThat(api.put("/api/meta/object-types/Flight", TestModel.objectType("Flight", "ds_flight",
                "flightNo", "flightNo", List.of(
                        TestModel.prop("flightNo", "string", "source", "flight_no", true, null),
                        TestModel.prop("departure", "string", "source", "departure", false, null),
                        TestModel.prop("arrival", "string", "source", "arrival", false, null),
                        TestModel.prop("status", "string", "ontology"),
                        TestModel.prop("reason", "string", "ontology"),
                        TestModel.prop("delayMinutes", "integer", "derived", null, false,
                                "link:DelayedBy.minutes"))), "admin").status()).isEqualTo(200);
        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);
    }
}
