package com.example.ontology.datasource;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.example.ontology.support.TestDatabase;
import com.example.ontology.support.TestModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量采集：对象映射、链接物化、派生计算、身份稳定、ontology 状态不被覆盖。
 */
class IngestTest extends BaseIntegrationTest {

    @Test
    void 采集全链路与重采的幂等性() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createTypes();
        createLinks();

        // 两阶段引入：先有类型与链接，再补跨类型派生属性（revision -> 2）
        List<Map<String, Object>> props = new ArrayList<>(List.of(
                TestModel.prop("flightNo", "string", "source", "flight_no", true, null),
                TestModel.prop("departure", "string", "source", "departure", false, null),
                TestModel.prop("arrival", "string", "source", "arrival", false, null),
                TestModel.prop("status", "string", "ontology"),
                TestModel.prop("reason", "string", "ontology"),
                TestModel.prop("delayMinutes", "integer", "derived", null, false, "link:DelayedBy.minutes")));
        Map<String, Object> withDerived = TestModel.objectType("Flight", "ds_flight", "flightNo", "flightNo", props);
        assertThat(api.put("/api/meta/object-types/Flight", withDerived, "admin").status()).isEqualTo(200);

        assertThat(api.post("/api/meta/action-types", TestModel.action("CancelFlight", "Flight",
                List.of(TestModel.objectRefParam("flightId", "Flight", false),
                        TestModel.param("reason", "string", true)),
                List.of(TestModel.precondition("notEquals", "status", "DEPARTED", "ORDER_DEPARTED", "已起飞")),
                TestModel.cancelMutations(), 1)).status()).isEqualTo(201);

        TestClient.Resp ingest = api.post("/api/ingest/all", null);
        assertThat(ingest.status()).isEqualTo(200);
        assertThat(ingest.at("objectTypes").size()).isEqualTo(5);
        assertThat(ingest.at("links").path("linksCreated").asInt()).isEqualTo(15);
        assertThat(countRows("obj_object")).isEqualTo(22);
        assertThat(countRows("obj_link")).isEqualTo(15);

        TestClient.Resp mu = api.get("/api/objects/Flight:MU5101");
        assertThat(mu.status()).isEqualTo(200);
        assertThat(mu.at("properties").path("departure").asText()).isEqualTo("PEK");
        assertThat(mu.at("properties").path("delayMinutes").asInt()).isEqualTo(35);
        assertThat(mu.str("stateOwner")).isEqualTo("source");

        TestClient.Resp cz = api.get("/api/objects/Flight:CZ3101");
        assertThat(cz.at("properties").has("delayMinutes")).isFalse();

        TestClient.Resp links = api.get("/api/objects/Flight:MU5101/links?linkType=DelayedBy");
        assertThat(links.status()).isEqualTo(200);
        assertThat(links.at("items").size()).isEqualTo(1);
        assertThat(links.at("items").get(0).path("objectId").asText()).isEqualTo("Delay:D001");

        List<String> idsBefore = jdbc.queryForList("select object_id from obj_object order by object_id", String.class);

        TestClient.Resp again = api.post("/api/ingest/all", null);
        assertThat(again.status()).isEqualTo(200);
        Map<String, Object> flightReport = null;
        for (var report : again.at("objectTypes")) {
            if ("Flight".equals(report.path("typeCode").asText())) {
                flightReport = mapper.convertValue(report, Map.class);
            }
        }
        assertThat(flightReport).isNotNull();
        assertThat(((Number) flightReport.get("created")).intValue()).isZero();
        assertThat(((Number) flightReport.get("updated")).intValue()).isEqualTo(6);

        List<String> idsAfter = jdbc.queryForList("select object_id from obj_object order by object_id", String.class);
        assertThat(idsAfter).isEqualTo(idsBefore);
        assertThat(idsAfter).contains("Flight:MU5101");

        // Action 写入的 ontology 状态不被重采覆盖
        TestClient.Resp cancel = api.post("/api/actions/CancelFlight/execute", Map.of(
                "params", Map.of("flightId", "Flight:MU5101", "reason", "天气"),
                "expectedVersion", 0,
                "idempotencyKey", "ing-" + UUID.randomUUID()));
        assertThat(cancel.status()).isEqualTo(200);

        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);
        TestClient.Resp after = api.get("/api/objects/Flight:MU5101");
        assertThat(after.at("properties").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(after.at("properties").path("departure").asText()).isEqualTo("PEK");
    }

    // ------------------------------------------------------------------
    private long registerSource() {
        TestClient.Resp resp = api.post("/api/datasources", Map.of(
                "name", "test-src", "kind", "POSTGRES",
                "host", TestDatabase.host(), "port", TestDatabase.port(),
                "database", TestDatabase.database(), "schema", "airline_domain",
                "username", TestDatabase.username(), "password", TestDatabase.password()));
        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.body().has("password")).isFalse();
        return resp.at("id").asLong();
    }

    private void registerDatasets(long sourceId) {
        String[][] ds = {
                {"ds_airport", "airport", "code"},
                {"ds_flight", "flight", "flight_no"},
                {"ds_aircraft", "aircraft", "tail_no"},
                {"ds_airline", "airline", "code"},
                {"ds_delay", "delay", "id"}
        };
        for (String[] row : ds) {
            TestClient.Resp resp = api.post("/api/datasets", Map.of(
                    "id", row[0], "name", row[0], "sourceId", sourceId,
                    "schema", "airline_domain", "table", row[1], "keyFields", row[2]));
            assertThat(resp.status()).isEqualTo(201);
        }
    }

    private void createTypes() {
        assertThat(api.post("/api/meta/object-types", TestModel.objectType("Airport", "ds_airport",
                "code", "name", List.of(
                        TestModel.prop("code", "string", "source", "code", true, null),
                        TestModel.prop("name", "string", "source", "name", false, null),
                        TestModel.prop("capacity", "long", "source", "capacity", false, null),
                        TestModel.prop("weather", "string", "derived", null, false, "src:weather_code"))))
                .status()).isEqualTo(201);

        assertThat(api.post("/api/meta/object-types", TestModel.objectType("Flight", "ds_flight",
                "flightNo", "flightNo", List.of(
                        TestModel.prop("flightNo", "string", "source", "flight_no", true, null),
                        TestModel.prop("departure", "string", "source", "departure", false, null),
                        TestModel.prop("arrival", "string", "source", "arrival", false, null),
                        TestModel.prop("status", "string", "ontology"),
                        TestModel.prop("reason", "string", "ontology"))))
                .status()).isEqualTo(201);

        assertThat(api.post("/api/meta/object-types", TestModel.objectType("Aircraft", "ds_aircraft",
                "tailNo", "model", List.of(
                        TestModel.prop("tailNo", "string", "source", "tail_no", true, null),
                        TestModel.prop("model", "string", "source", "model", false, null),
                        TestModel.prop("capacity", "integer", "source", "capacity", false, null))))
                .status()).isEqualTo(201);

        assertThat(api.post("/api/meta/object-types", TestModel.objectType("Airline", "ds_airline",
                "code", "name", List.of(
                        TestModel.prop("code", "string", "source", "code", true, null),
                        TestModel.prop("name", "string", "source", "name", false, null))))
                .status()).isEqualTo(201);

        assertThat(api.post("/api/meta/object-types", TestModel.objectType("Delay", "ds_delay",
                "id", "cause", List.of(
                        TestModel.prop("id", "string", "source", "id", true, null),
                        TestModel.prop("minutes", "integer", "source", "minutes", false, null),
                        TestModel.prop("cause", "string", "source", "cause", false, null))))
                .status()).isEqualTo(201);
    }

    private void createLinks() {
        assertThat(api.post("/api/meta/link-types", TestModel.linkType("OperatedBy", "Flight", "Airline",
                "N:M", "carrier_code", "code")).status()).isEqualTo(201);
        assertThat(api.post("/api/meta/link-types", TestModel.linkType("FlownBy", "Flight", "Aircraft",
                "N:1", "tail_no", "tail_no")).status()).isEqualTo(201);
        assertThat(api.post("/api/meta/link-types", TestModel.linkType("DelayedBy", "Flight", "Delay",
                "1:1", "delay_id", "id")).status()).isEqualTo(201);
    }
}
