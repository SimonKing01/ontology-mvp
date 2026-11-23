package com.example.ontology.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 测试用 HTTP 客户端（HTTP Basic + JSON）。
 */
public class TestClient {

    private final TestRestTemplate rest;
    private final ObjectMapper mapper;

    public TestClient(TestRestTemplate rest, ObjectMapper mapper) {
        this.rest = rest;
        this.mapper = mapper;
    }

    public record Resp(int status, JsonNode body) {

        public String str(String field) {
            JsonNode node = body == null ? null : body.get(field);
            return node == null || node.isNull() ? null : node.asText();
        }

        public JsonNode at(String field) {
            return body == null ? null : body.path(field);
        }
    }

    public Resp post(String path, Object body) {
        return post(path, body, "admin");
    }

    public Resp post(String path, Object body, String user) {
        return exchange(HttpMethod.POST, path, body, user);
    }

    public Resp put(String path, Object body, String user) {
        return exchange(HttpMethod.PUT, path, body, user);
    }

    public Resp get(String path) {
        return get(path, "admin");
    }

    public Resp get(String path, String user) {
        return exchange(HttpMethod.GET, path, null, user);
    }

    public Resp delete(String path, String user) {
        return exchange(HttpMethod.DELETE, path, null, user);
    }

    private Resp exchange(HttpMethod method, String path, Object body, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json;
        try {
            json = body == null ? null : (body instanceof String s ? s : mapper.writeValueAsString(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        ResponseEntity<String> response = rest.withBasicAuth(user, user)
                .exchange(path, method, new HttpEntity<>(json, headers), String.class);
        try {
            String text = response.getBody();
            JsonNode node = text == null || text.isBlank() ? null : mapper.readTree(text);
            return new Resp(response.getStatusCode().value(), node);
        } catch (Exception ex) {
            throw new IllegalStateException("响应无法解析: " + response.getBody(), ex);
        }
    }
}
