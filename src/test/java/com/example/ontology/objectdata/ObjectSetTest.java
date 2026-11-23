package com.example.ontology.objectdata;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象集：保存的是条件不是结果，所以能被复用、共享、当作查询输入。
 */
class ObjectSetTest extends BaseIntegrationTest {

    private static final Map<String, Object> PEK_FILTER = Map.of(
            "prop", "departure", "op", "eq", "value", "PEK");

    @Test
    void 保存集合并用它查询() {
        prepare();

        TestClient.Resp created = api.post("/api/object-sets", Map.of(
                "name", "华北出发航班", "typeCode", "Flight",
                "filters", List.of(PEK_FILTER), "shared", false));
        assertThat(created.status()).isEqualTo(200);
        String id = created.str("id");
        assertThat(id).startsWith("os_");

        // 用集合查询：等价于直接传 filters，但不用重复写条件
        TestClient.Resp q = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight", "pageSize", 20, "objectSetId", id));
        assertThat(q.status()).isEqualTo(200);
        assertThat(q.at("items").size()).isEqualTo(2);
        for (JsonNode item : q.at("items")) {
            assertThat(item.get("properties").get("departure").asText()).isEqualTo("PEK");
        }

        // 列表里能看到
        TestClient.Resp list = api.get("/api/object-sets");
        assertThat(list.status()).isEqualTo(200);
        boolean found = false;
        for (JsonNode node : list.body()) {
            if (id.equals(node.get("id").asText())) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void 集合类型与查询类型不一致返回400() {
        prepare();
        String id = api.post("/api/object-sets", Map.of(
                "name", "PEK", "typeCode", "Flight",
                "filters", List.of(PEK_FILTER))).str("id");

        TestClient.Resp resp = api.post("/api/objects/query", Map.of(
                "typeCode", "Airport", "pageSize", 20, "objectSetId", id));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("不一致");
    }

    @Test
    void 未共享的集合别人不能读() {
        prepare();
        String id = api.post("/api/object-sets", Map.of(
                "name", "admin 的集合", "typeCode", "Flight",
                "filters", List.of(PEK_FILTER), "shared", false)).str("id");

        TestClient.Resp denied = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight", "pageSize", 20, "objectSetId", id), "viewer");
        assertThat(denied.status()).isEqualTo(403);
    }

    @Test
    void 共享后别人可以读但只有创建者能删() {
        prepare();
        String id = api.post("/api/object-sets", Map.of(
                "name", "共享集合", "typeCode", "Flight",
                "filters", List.of(PEK_FILTER), "shared", true)).str("id");

        TestClient.Resp ok = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight", "pageSize", 20, "objectSetId", id), "viewer");
        assertThat(ok.status()).isEqualTo(200);

        TestClient.Resp deniedDelete = api.delete("/api/object-sets/" + id, "viewer");
        assertThat(deniedDelete.status()).isEqualTo(403);

        TestClient.Resp deleted = api.delete("/api/object-sets/" + id, "admin");
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(api.get("/api/object-sets/" + id).status()).isEqualTo(404);
    }

    @Test
    void 归属属性不存在的对象集查询会报错() {
        prepare();
        String id = api.post("/api/object-sets", Map.of(
                "name", "坏条件", "typeCode", "Flight",
                "filters", List.of(Map.of("prop", "noSuchProp", "op", "eq", "value", "x")))).str("id");

        TestClient.Resp resp = api.post("/api/objects/query", Map.of(
                "typeCode", "Flight", "pageSize", 20, "objectSetId", id));
        assertThat(resp.status()).isEqualTo(400);
    }

    private void prepare() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();
        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);
    }
}
