package com.example.ontology.datasource;

import com.example.ontology.objectdata.store.ObjectStore;
import com.example.ontology.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链接墓碑：Action 删掉的链接不该被重采按上游事实"复活"。
 *
 * <p>场景：航班 MU5101 上游 tail_no 是 B-1234，用户把它改派到 B-5678。
 * 重采时源表仍说 B-1234，如果没有墓碑，链接会被重新插回来（用户看到两条 FlownBy）。</p>
 */
class LinkTombstoneTest extends BaseIntegrationTest {

    @Autowired
    ObjectStore store;

    @Test
    void Action_删掉的链接重采不复活_重建时清除墓碑() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();

        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);
        assertThat(countRows("obj_link")).isEqualTo(15);
        assertThat(store.linkExists("FlownBy", "Flight:MU5101", "Aircraft:B-1234")).isTrue();

        // 1) Action 删掉这条链接 → 墓碑留下
        store.deleteLink("FlownBy", "Flight:MU5101", "Aircraft:B-1234", "tester", "run-x");
        assertThat(store.linkExists("FlownBy", "Flight:MU5101", "Aircraft:B-1234")).isFalse();
        assertThat(countRows("obj_link_tombstone")).isEqualTo(1);

        // 2) 重采：上游仍说 tail_no=B-1234，但有墓碑，不能重建
        assertThat(api.post("/api/ingest/links", null).status()).isEqualTo(200);
        assertThat(store.linkExists("FlownBy", "Flight:MU5101", "Aircraft:B-1234")).isFalse();
        assertThat(countRows("obj_link_tombstone")).isEqualTo(1);

        // 3) Action 主动重建同一条链接 → 墓碑清除，链接回来
        store.insertLink("FlownBy", "Flight:MU5101", "Aircraft:B-1234", Map.of());
        assertThat(store.linkExists("FlownBy", "Flight:MU5101", "Aircraft:B-1234")).isTrue();
        assertThat(countRows("obj_link_tombstone")).isZero();

        // 4) 再采一次：链接保留，不会被"清理"
        assertThat(api.post("/api/ingest/links", null).status()).isEqualTo(200);
        assertThat(store.linkExists("FlownBy", "Flight:MU5101", "Aircraft:B-1234")).isTrue();
    }

    @Test
    void 按类型批量删除也留墓碑() {
        long sourceId = registerSource();
        registerDatasets(sourceId);
        createBaseTypes();
        createBaseLinks();
        assertThat(api.post("/api/ingest/all", null).status()).isEqualTo(200);

        // MU5101 原有 OperatedBy / DelayedBy / FlownBy 三条
        store.deleteLinksOfType("FlownBy", "Flight:MU5101", "tester", "run-y");
        assertThat(countRows("obj_link_tombstone")).isEqualTo(1);

        assertThat(api.post("/api/ingest/links", null).status()).isEqualTo(200);
        assertThat(store.linkExists("FlownBy", "Flight:MU5101", "Aircraft:B-1234")).isFalse();
        // 其他类型不受影响
        assertThat(store.linkExists("OperatedBy", "Flight:MU5101", "Airline:MU")).isTrue();
    }
}
