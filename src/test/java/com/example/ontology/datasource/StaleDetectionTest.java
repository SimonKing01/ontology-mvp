package com.example.ontology.datasource;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除检测：上游删掉的行，对象不物理删除，而是标记 stale。
 *
 * <p>为什么不是物理删除：对象上可能挂着 Action 改过的 ontology 状态，
 * 直接删会把"某人的决定"一起抹掉。标记失效既保留了历史，也让查询能区分。</p>
 */
class StaleDetectionTest extends BaseIntegrationTest {

    private static final String RESTORE_MU5102 = """
            insert into airline_domain.flight (flight_no, departure, arrival, carrier_code, tail_no, delay_id)
            values ('MU5102', 'PVG', 'PEK', 'MU', 'B-1234', null)
            on conflict (flight_no) do nothing
            """;

    @Test
    void 上游删除后标记失效_恢复后自动清除() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();
        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);
        assertThat(staleOf("Flight:MU5102")).isFalse();

        jdbc.update("delete from airline_domain.flight where flight_no = 'MU5102'");
        try {
            TestClient.Resp ingest = api.post("/api/ingest/object-types/Flight", null);
            assertThat(ingest.status()).isEqualTo(200);
            assertThat(ingest.at("readRows").asInt()).isEqualTo(5);
            assertThat(ingest.at("staleMarked").asInt()).isEqualTo(1);

            assertThat(staleOf("Flight:MU5102")).isTrue();
            // 对象没有被物理删除，还能查到，只是带 stale 标记
            TestClient.Resp got = api.get("/api/objects/Flight:MU5102");
            assertThat(got.status()).isEqualTo(200);
            assertThat(got.at("stale").asBoolean()).isTrue();
        } finally {
            jdbc.update(RESTORE_MU5102);
        }

        assertThat(api.post("/api/ingest/object-types/Flight", null).status()).isEqualTo(200);
        assertThat(staleOf("Flight:MU5102")).isFalse();
    }

    @Test
    void 本体创建的对象不会因为没扫到而失效() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();
        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);

        seedObject("Flight", "LOCAL1", 0, "ontology", "本地创建");
        assertThat(api.post("/api/ingest/object-types/Flight", null).status()).isEqualTo(200);

        assertThat(staleOf("Flight:LOCAL1")).isFalse();
    }

    private boolean staleOf(String objectId) {
        Boolean b = jdbc.queryForObject("select stale from obj_object where object_id = ?",
                Boolean.class, objectId);
        return b != null && b;
    }
}
