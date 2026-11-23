package com.example.ontology.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 当前会话信息（只读，供演示控制台显示角色）。
 *
 * <p>没有任何写能力，也不引入新的写路径。</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "会话", description = "当前登录用户与角色")
public class SessionController {

    private final OntologyAuthorizer authorizer;

    public SessionController(OntologyAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @GetMapping("/session")
    @Operation(summary = "当前登录用户、角色与可见类型（演示控制台使用）")
    public Map<String, Object> session() {
        Set<String> roles = authorizer.currentRoles();
        String role = roles.contains("ADMIN") ? "ADMIN"
                : roles.contains("EDITOR") ? "EDITOR"
                : roles.contains("VIEWER") ? "VIEWER" : "NONE";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", authorizer.currentActor());
        body.put("role", role);
        body.put("roles", roles);
        return body;
    }
}
