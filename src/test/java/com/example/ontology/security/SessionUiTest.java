package com.example.ontology.security;

import com.example.ontology.support.BaseIntegrationTest;
import com.example.ontology.support.TestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示控制台：静态外壳匿名可打开，数据接口仍然必须认证。
 */
class SessionUiTest extends BaseIntegrationTest {

    @Test
    void 会话接口返回当前角色() {
        TestClient.Resp admin = api.get("/api/session");
        assertThat(admin.status()).isEqualTo(200);
        assertThat(admin.str("username")).isEqualTo("admin");
        assertThat(admin.str("role")).isEqualTo("ADMIN");

        TestClient.Resp viewer = api.get("/api/session", "viewer");
        assertThat(viewer.status()).isEqualTo(200);
        assertThat(viewer.str("role")).isEqualTo("VIEWER");
    }

    @Test
    void 控制台静态页可匿名打开但接口仍需认证() {
        ResponseEntity<String> page = rest.getForEntity("/", String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("本体控制台");

        ResponseEntity<String> script = rest.getForEntity("/app.js", String.class);
        assertThat(script.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> style = rest.getForEntity("/style.css", String.class);
        assertThat(style.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> metaScript = rest.getForEntity("/meta.js", String.class);
        assertThat(metaScript.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> coreScript = rest.getForEntity("/core.js", String.class);
        assertThat(coreScript.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> viewScript = rest.getForEntity("/views/traverse.js", String.class);
        assertThat(viewScript.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> count = rest.getForEntity("/api/objects/count", String.class);
        assertThat(count.getStatusCode().value()).isEqualTo(401);
    }
}
