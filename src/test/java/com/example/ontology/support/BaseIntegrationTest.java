package com.example.ontology.support;

import com.example.ontology.meta.MetamodelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/**
 * 集成测试基座：Testcontainers PostgreSQL 16（不可用时回退到 TEST_DB_URL），每个用例清库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.com.example.ontology=DEBUG",
                "logging.level.com.example.ontology.objectdata.ObjectQuerySqlBuilder=DEBUG"
        })
public abstract class BaseIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::username);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MetamodelRegistry metamodel;

    protected TestClient api;

    @BeforeEach
    void resetDatabase() {
        api = new TestClient(rest, mapper);
        jdbc.execute("""
                truncate table act_edit, act_run, obj_link_tombstone, obj_link, obj_prop, obj_object, obj_set,
                ont_definition_history, ont_definition restart identity cascade
                """);
        jdbc.execute("truncate table ds_dataset, ds_source restart identity cascade");
        metamodel.reload();
    }

    // ------------------------------------------------------------------
    // 航空域夹具（IngestTest 与 LinkTombstoneTest 共用）
    // ------------------------------------------------------------------
    protected long registerSource() {
        TestClient.Resp resp = api.post("/api/datasources", java.util.Map.of(
                "name", "test-src", "kind", "POSTGRES",
                "host", TestDatabase.host(), "port", TestDatabase.port(),
                "database", TestDatabase.database(), "schema", "airline_domain",
                "username", TestDatabase.username(), "password", TestDatabase.password()));
        assert resp.status() == 201;
        return resp.at("id").asLong();
    }

    protected void registerDatasets(long sourceId) {
        String[][] ds = {
                {"ds_airport", "airport", "code"},
                {"ds_flight", "flight", "flight_no"},
                {"ds_aircraft", "aircraft", "tail_no"},
                {"ds_airline", "airline", "code"},
                {"ds_delay", "delay", "id"}
        };
        for (String[] row : ds) {
            TestClient.Resp resp = api.post("/api/datasets", java.util.Map.of(
                    "id", row[0], "name", row[0], "sourceId", sourceId,
                    "schema", "airline_domain", "table", row[1], "keyFields", row[2]));
            assert resp.status() == 201;
        }
    }

    protected void createBaseTypes() {
        api.post("/api/meta/object-types", TestModel.objectType("Airport", "ds_airport",
                "code", "name", java.util.List.of(
                        TestModel.prop("code", "string", "source", "code", true, null),
                        TestModel.prop("name", "string", "source", "name", false, null),
                        TestModel.prop("capacity", "long", "source", "capacity", false, null),
                        TestModel.prop("weather", "string", "derived", null, false, "src:weather_code"))));

        api.post("/api/meta/object-types", TestModel.objectType("Flight", "ds_flight",
                "flightNo", "flightNo", java.util.List.of(
                        TestModel.prop("flightNo", "string", "source", "flight_no", true, null),
                        TestModel.prop("departure", "string", "source", "departure", false, null),
                        TestModel.prop("arrival", "string", "source", "arrival", false, null),
                        TestModel.prop("status", "string", "ontology"),
                        TestModel.prop("reason", "string", "ontology"))));

        api.post("/api/meta/object-types", TestModel.objectType("Aircraft", "ds_aircraft",
                "tailNo", "model", java.util.List.of(
                        TestModel.prop("tailNo", "string", "source", "tail_no", true, null),
                        TestModel.prop("model", "string", "source", "model", false, null),
                        TestModel.prop("capacity", "integer", "source", "capacity", false, null))));

        api.post("/api/meta/object-types", TestModel.objectType("Airline", "ds_airline",
                "code", "name", java.util.List.of(
                        TestModel.prop("code", "string", "source", "code", true, null),
                        TestModel.prop("name", "string", "source", "name", false, null))));

        api.post("/api/meta/object-types", TestModel.objectType("Delay", "ds_delay",
                "id", "cause", java.util.List.of(
                        TestModel.prop("id", "string", "source", "id", true, null),
                        TestModel.prop("minutes", "integer", "source", "minutes", false, null),
                        TestModel.prop("cause", "string", "source", "cause", false, null))));
    }

    protected void createBaseLinks() {
        api.post("/api/meta/link-types", TestModel.linkType("OperatedBy", "Flight", "Airline",
                "N:M", "carrier_code", "code"));
        api.post("/api/meta/link-types", TestModel.linkType("FlownBy", "Flight", "Aircraft",
                "N:1", "tail_no", "tail_no"));
        api.post("/api/meta/link-types", TestModel.linkType("DelayedBy", "Flight", "Delay",
                "1:1", "delay_id", "id"));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------
    protected void seedObject(String typeCode, String businessKey, long version, String stateOwner, String title) {
        jdbc.update("""
                        insert into obj_object (object_id, type_code, business_key, row_version, state_owner, title)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                typeCode + ":" + businessKey, typeCode, businessKey, version, stateOwner, title);
    }

    protected void seedStringProp(String objectId, String propKey, String value) {
        jdbc.update("""
                        insert into obj_prop (object_id, prop_key, ord, v_str) values (?, ?, 0, ?)
                        on conflict (object_id, prop_key, ord) do update set v_str = excluded.v_str
                        """, objectId, propKey, value);
    }

    protected void seedNumberProp(String objectId, String propKey, Number value) {
        jdbc.update("""
                        insert into obj_prop (object_id, prop_key, ord, v_num) values (?, ?, 0, ?)
                        on conflict (object_id, prop_key, ord) do update set v_num = excluded.v_num
                        """, objectId, propKey, value);
    }

    protected long versionOf(String objectId) {
        Long v = jdbc.queryForObject("select row_version from obj_object where object_id = ?",
                Long.class, objectId);
        return v == null ? -1 : v;
    }

    protected long countRows(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    protected String randomKey() {
        return UUID.randomUUID().toString();
    }
}
