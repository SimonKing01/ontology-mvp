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
                truncate table act_edit, act_run, obj_link, obj_prop, obj_object,
                ont_definition_history, ont_definition restart identity cascade
                """);
        jdbc.execute("truncate table ds_dataset, ds_source restart identity cascade");
        metamodel.reload();
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
