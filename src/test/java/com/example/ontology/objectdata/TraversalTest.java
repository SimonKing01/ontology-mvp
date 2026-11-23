package com.example.ontology.objectdata;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多跳遍历：一条请求走完 Delay → Flight → Airline。
 */
class TraversalTest extends BaseIntegrationTest {

    private static final Map<String, Object> DELAY_TO_AIRLINE = Map.of("path", List.of(
            Map.of("linkType", "DelayedBy", "direction", "IN"),
            Map.of("linkType", "OperatedBy", "direction", "OUT")));

    @Test
    void 两跳从延误原因走到承运航司() {
        prepare();

        TestClient.Resp resp = api.post("/api/objects/Delay:D001/traverse", DELAY_TO_AIRLINE);
        assertThat(resp.status()).isEqualTo(200);

        JsonNode body = resp.body();
        assertThat(body.get("startType").asText()).isEqualTo("Delay");
        assertThat(body.get("steps").size()).isEqualTo(2);
        assertThat(body.get("truncated").asBoolean()).isFalse();

        // 第一跳：D001 只挂在 MU5101 上
        JsonNode step1 = body.get("steps").get(0);
        assertThat(step1.get("linkType").asText()).isEqualTo("DelayedBy");
        assertThat(step1.get("direction").asText()).isEqualTo("IN");
        assertThat(step1.get("targetType").asText()).isEqualTo("Flight");
        assertThat(step1.get("count").asInt()).isEqualTo(1);
        assertThat(step1.get("items").get(0).get("objectId").asText()).isEqualTo("Flight:MU5101");

        // 第二跳：MU5101 承运方是东航
        JsonNode finals = body.get("finalNodes");
        assertThat(finals.size()).isEqualTo(1);
        assertThat(finals.get(0).get("objectId").asText()).isEqualTo("Airline:MU");
        // 每个节点都带 viaLinkId，前端才能画出"通过哪条边过来的"
        assertThat(finals.get(0).get("viaLinkId").asText()).startsWith("OperatedBy:");
    }

    @Test
    void 深度超过上限返回400() {
        prepare();
        Map<String, Object> deep = Map.of("path", List.of(
                Map.of("linkType", "DelayedBy", "direction", "IN"),
                Map.of("linkType", "OperatedBy", "direction", "OUT"),
                Map.of("linkType", "OperatedBy", "direction", "IN"),
                Map.of("linkType", "OperatedBy", "direction", "OUT")));
        TestClient.Resp resp = api.post("/api/objects/Delay:D001/traverse", deep);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("深度上限");
    }

    @Test
    void 不存在的链接类型返回400() {
        prepare();
        TestClient.Resp resp = api.post("/api/objects/Flight:MU5101/traverse", Map.of("path", List.of(
                Map.of("linkType", "NoSuchLink", "direction", "OUT"))));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("链接类型不存在");
    }

    @Test
    void viewer_不能借遍历穿透到不可读类型() {
        prepare();

        // viewer 可读 Flight，但不可读 Aircraft
        TestClient.Resp ok = api.post("/api/objects/Flight:MU5101/traverse", Map.of("path", List.of(
                Map.of("linkType", "OperatedBy", "direction", "OUT"))), "viewer");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("finalNodes").get(0).get("objectId").asText()).isEqualTo("Airline:MU");

        TestClient.Resp denied = api.post("/api/objects/Flight:MU5101/traverse", Map.of("path", List.of(
                Map.of("linkType", "FlownBy", "direction", "OUT"))), "viewer");
        assertThat(denied.status()).isEqualTo(403);
    }

    @Test
    void 起点不存在返回404() {
        prepare();
        TestClient.Resp resp = api.post("/api/objects/Flight:NOPE/traverse", Map.of("path", List.of(
                Map.of("linkType", "OperatedBy", "direction", "OUT"))));
        assertThat(resp.status()).isEqualTo(404);
    }

    private void prepare() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();
        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);
    }
}
