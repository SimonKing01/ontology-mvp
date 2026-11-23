package com.example.ontology.action;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import com.example.ontology.support.TestModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Action 语义：幂等、乐观锁（含并发）、前置条件、maxObjects、唯一性、越权裁剪、审计。
 */
class ActionSemanticsTest extends BaseIntegrationTest {

    @BeforeEach
    void setUpModel() {
        assertThat(api.post("/api/meta/object-types", TestModel.flightType("Flight")).status()).isEqualTo(201);

        assertThat(api.post("/api/meta/action-types", TestModel.action("CancelFlight", "Flight",
                List.of(TestModel.objectRefParam("flightId", "Flight", false),
                        TestModel.param("reason", "string", true)),
                List.of(TestModel.precondition("notEquals", "status", "DEPARTED", "ORDER_DEPARTED", "已起飞航班不能取消")),
                TestModel.cancelMutations(), 1)).status()).isEqualTo(201);

        assertThat(api.post("/api/meta/action-types", TestModel.action("BatchCloseFlights", "Flight",
                List.of(TestModel.objectRefParam("flights", "Flight", true),
                        TestModel.param("reason", "string", true)),
                List.of(TestModel.precondition("notEquals", "status", "CLOSED", "ALREADY_CLOSED", "航班已关闭")),
                List.of(TestModel.update("status", "literal:'CLOSED'"),
                        TestModel.update("reason", "param:reason")), 2)).status()).isEqualTo(201);

        assertThat(api.post("/api/meta/action-types", TestModel.action("CreateFlight", "Flight",
                List.of(TestModel.param("flightNo", "string", true)),
                List.of(),
                List.of(TestModel.create("param:flightNo", "param:flightNo",
                        Map.of("status", "literal:'SCHEDULED'"))), 1)).status()).isEqualTo(201);
    }

    private Map<String, Object> cancelBody(String objectId, long version, String key) {
        return Map.of(
                "params", Map.of("flightId", objectId, "reason", "天气"),
                "expectedVersion", version,
                "idempotencyKey", key);
    }

    @Test
    void 取消航班_版本加一_并留下审计() {
        seedObject("Flight", "MU1", 0, "source", "MU1");
        seedStringProp("Flight:MU1", "status", "SCHEDULED");

        TestClient.Resp resp = api.post("/api/actions/CancelFlight/execute",
                cancelBody("Flight:MU1", 0, "k-" + UUID.randomUUID()));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.at("result").path("targets").get(0).path("version").asLong()).isEqualTo(1);
        assertThat(versionOf("Flight:MU1")).isEqualTo(1);
        assertThat(countRows("act_run")).isEqualTo(1);
        assertThat(countRows("act_edit")).isEqualTo(2);

        String runId = resp.str("runId");
        TestClient.Resp audit = api.get("/api/audit/runs/" + runId);
        assertThat(audit.status()).isEqualTo(200);
        assertThat(audit.at("run").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(audit.at("edits").size()).isEqualTo(2);
        assertThat(audit.at("edits").get(0).path("editType").asText()).isEqualTo("UPDATE");
    }

    @Test
    void 同一幂等键重放只产生一次变更() {
        seedObject("Flight", "MU1", 0, "source", "MU1");
        seedStringProp("Flight:MU1", "status", "SCHEDULED");
        String key = "idem-" + UUID.randomUUID();
        Map<String, Object> body = cancelBody("Flight:MU1", 0, key);

        TestClient.Resp first = api.post("/api/actions/CancelFlight/execute", body);
        TestClient.Resp replay = api.post("/api/actions/CancelFlight/execute", body);

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.at("idempotentReplay").asBoolean()).isTrue();
        assertThat(replay.str("runId")).isEqualTo(first.str("runId"));
        assertThat(countRows("act_edit")).isEqualTo(2);
        assertThat(versionOf("Flight:MU1")).isEqualTo(1);
    }

    @Test
    void 版本不一致返回_409() {
        seedObject("Flight", "MU1", 5, "source", "MU1");
        seedStringProp("Flight:MU1", "status", "SCHEDULED");

        TestClient.Resp resp = api.post("/api/actions/CancelFlight/execute",
                cancelBody("Flight:MU1", 4, "k-" + UUID.randomUUID()));

        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.at("details").path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(versionOf("Flight:MU1")).isEqualTo(5);
        assertThat(countRows("act_run")).isZero();
    }

    @Test
    void 并发两次改同一对象_恰好一次成功一次_409() throws Exception {
        seedObject("Flight", "MU1", 0, "source", "MU1");
        seedStringProp("Flight:MU1", "status", "SCHEDULED");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> task = () -> {
                start.await();
                return api.post("/api/actions/CancelFlight/execute",
                        cancelBody("Flight:MU1", 0, "concurrent-" + UUID.randomUUID())).status();
            };
            Future<Integer> f1 = pool.submit(task);
            Future<Integer> f2 = pool.submit(task);
            start.countDown();
            int s1 = f1.get();
            int s2 = f2.get();

            assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 409);
            assertThat(versionOf("Flight:MU1")).isEqualTo(1);
            assertThat(countRows("act_edit")).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 前置条件失败返回_422_且带_error_code() {
        seedObject("Flight", "MU1", 0, "source", "MU1");
        seedStringProp("Flight:MU1", "status", "DEPARTED");

        TestClient.Resp resp = api.post("/api/actions/CancelFlight/execute",
                cancelBody("Flight:MU1", 0, "k-" + UUID.randomUUID()));

        assertThat(resp.status()).isEqualTo(422);
        assertThat(resp.str("code")).isEqualTo("PRECONDITION_FAILED");
        assertThat(resp.at("details").path("errorCode").asText()).isEqualTo("ORDER_DEPARTED");
        assertThat(versionOf("Flight:MU1")).isZero();
        // 事务整体回滚：没有留下 PENDING 的 act_run
        assertThat(countRows("act_run")).isZero();
    }

    @Test
    void 超过_maxObjects_返回_400_并提示分片() {
        for (String key : List.of("A1", "A2", "A3")) {
            seedObject("Flight", key, 0, "source", key);
        }
        TestClient.Resp resp = api.post("/api/actions/BatchCloseFlights/execute", Map.of(
                "params", Map.of("flights", List.of("Flight:A1", "Flight:A2", "Flight:A3"), "reason", "台风"),
                "expectedVersion", 0,
                "idempotencyKey", "k-" + UUID.randomUUID()));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("code")).isEqualTo("LIMIT_EXCEEDED");
        assertThat(resp.at("details").path("maxObjects").asInt()).isEqualTo(2);
    }

    @Test
    void 同类型同_businessKey_不能重复创建() {
        Map<String, Object> body = Map.of(
                "params", Map.of("flightNo", "MU9"),
                "expectedVersion", 0,
                "idempotencyKey", "create-" + UUID.randomUUID());

        TestClient.Resp first = api.post("/api/actions/CreateFlight/execute", body);
        TestClient.Resp second = api.post("/api/actions/CreateFlight/execute", Map.of(
                "params", Map.of("flightNo", "MU9"),
                "expectedVersion", 0,
                "idempotencyKey", "create-" + UUID.randomUUID()));

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(409);
        assertThat(countRows("obj_object")).isEqualTo(1);
    }

    @Test
    void 缺少乐观锁版本返回_400() {
        seedObject("Flight", "MU1", 0, "source", "MU1");
        TestClient.Resp resp = api.post("/api/actions/CancelFlight/execute", Map.of(
                "params", Map.of("flightId", "Flight:MU1", "reason", "x"),
                "idempotencyKey", "k-" + UUID.randomUUID()));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.str("message")).contains("expectedVersion");
    }

    @Test
    void VIEWER_不能执行_Action_且属性被裁剪() {
        seedObject("Flight", "MU1", 0, "source", "MU1");
        seedStringProp("Flight:MU1", "flightNo", "MU1");
        seedStringProp("Flight:MU1", "status", "SCHEDULED");
        seedStringProp("Flight:MU1", "reason", "内部处置意见");

        TestClient.Resp forbidden = api.post("/api/actions/CancelFlight/execute",
                cancelBody("Flight:MU1", 0, "v-" + UUID.randomUUID()), "viewer");
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.str("code")).isEqualTo("FORBIDDEN");

        TestClient.Resp trimmed = api.get("/api/objects/Flight:MU1?fields=flightNo,reason", "viewer");
        assertThat(trimmed.status()).isEqualTo(200);
        assertThat(trimmed.at("properties").has("flightNo")).isTrue();
        assertThat(trimmed.at("properties").has("reason")).isFalse();
    }
}
