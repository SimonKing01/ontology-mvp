package com.example.ontology.meta;

import com.example.ontology.common.OntologyException;
import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.example.ontology.support.TestModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 元模型版本化（R8）与启动期校验（R7）。
 */
class MetamodelVersioningTest extends BaseIntegrationTest {

    @Test
    void 修改定义后_revision_递增且旧版本可查() {
        Map<String, Object> type = TestModel.flightType("Flight");
        TestClient.Resp created = api.post("/api/meta/object-types", type);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.at("revision").asInt()).isEqualTo(1);

        Map<String, Object> updated = new LinkedHashMap<>(type);
        List<Map<String, Object>> props = new ArrayList<>((List<Map<String, Object>>) type.get("properties"));
        props.add(TestModel.prop("note", "string", "ontology"));
        updated.put("properties", props);

        TestClient.Resp modified = api.put("/api/meta/object-types/Flight", updated, "admin");
        assertThat(modified.status()).isEqualTo(200);
        assertThat(modified.at("revision").asInt()).isEqualTo(2);

        TestClient.Resp old = api.get("/api/meta/object-types/Flight?revision=1");
        assertThat(old.status()).isEqualTo(200);
        assertThat(old.at("definition").path("properties").toString()).doesNotContain("note");

        TestClient.Resp current = api.get("/api/meta/object-types/Flight");
        assertThat(current.at("definition").path("properties").toString()).contains("note");

        TestClient.Resp history = api.get("/api/meta/object-types/Flight/history");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().isArray()).isTrue();
        assertThat(history.body().size()).isEqualTo(1);
        assertThat(history.body().get(0).path("revision").asInt()).isEqualTo(1);

        assertThat(api.get("/api/meta/object-types/Flight?revision=99").status()).isEqualTo(404);
    }

    @Test
    void code_创建后不可修改() {
        api.post("/api/meta/object-types", TestModel.flightType("Flight"));
        Map<String, Object> renamed = new LinkedHashMap<>(TestModel.flightType("Flight"));
        renamed.put("code", "Flight2");

        TestClient.Resp resp = api.put("/api/meta/object-types/Flight", renamed, "admin");

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("code 创建后不可修改");
    }

    @Test
    void 启动期校验会拒绝修改_source_属性的_Action() {
        assertThat(api.post("/api/meta/object-types", TestModel.flightType("Flight")).status()).isEqualTo(201);

        // 绕过 API 直接写库：模拟"有人手工改了定义"或"引入了一份坏的定义"
        String bad = """
                {"code":"BadAction","name":"bad","targetTypeCode":"Flight",
                 "params":[],"preconditions":[],
                 "mutations":[{"op":"update","prop":"flightNo","valueExpr":"literal:'X'"}],
                 "limits":{"maxObjects":10}}
                """;
        jdbc.update("""
                        insert into ont_definition (kind, code, revision, payload, created_at, updated_at)
                        values ('ACTION_TYPE', 'BadAction', 1, cast(? as jsonb), now(), now())
                        """, bad);

        assertThatThrownBy(() -> metamodel.reload())
                .isInstanceOf(OntologyException.class)
                .hasMessageContaining("元模型校验失败");

        // 运行中的快照不受影响：坏定义没有生效
        assertThat(metamodel.snapshot().actions()).doesNotContainKey("BadAction");
        assertThat(metamodel.snapshot().objectTypes()).containsKey("Flight");
    }
}
