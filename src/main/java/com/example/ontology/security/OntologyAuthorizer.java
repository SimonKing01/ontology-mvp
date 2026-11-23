package com.example.ontology.security;

import com.example.ontology.common.OntologyException;
import com.example.ontology.config.OntologyProperties;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PropertyDef;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本体授权器：按 type / action / property 动态判断。
 *
 * <p>三层权限：</p>
 * <ol>
 *   <li>type 级：能否看这个类型（查询入口就拦）</li>
 *   <li>action 级：能否执行这个 Action</li>
 *   <li>property 级：返回时裁剪 —— 必须在服务端做，调用方想拿什么不能拿什么</li>
 * </ol>
 */
@Component
public class OntologyAuthorizer {

    private final OntologyProperties properties;

    public OntologyAuthorizer(OntologyProperties properties) {
        this.properties = properties;
    }

    public String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }

    public Set<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toSet());
    }

    /** 取最高优先级的角色配置（ADMIN > EDITOR > VIEWER）。 */
    public OntologyProperties.RoleAcl effectiveAcl() {
        Set<String> roles = currentRoles();
        for (String role : List.of("ADMIN", "EDITOR", "VIEWER")) {
            if (roles.contains(role)) {
                OntologyProperties.RoleAcl acl = properties.aclOrEmpty().get(role);
                if (acl != null) {
                    return acl;
                }
            }
        }
        return new OntologyProperties.RoleAcl(List.of(), List.of(), java.util.Map.of());
    }

    public boolean canReadType(String typeCode) {
        List<String> types = effectiveAcl().typesOrEmpty();
        return types.contains("*") || types.contains(typeCode);
    }

    public void requireReadType(String typeCode) {
        if (!canReadType(typeCode)) {
            throw OntologyException.forbidden("无权访问对象类型: " + typeCode);
        }
    }

    public boolean canExecute(String actionCode) {
        List<String> actions = effectiveAcl().actionsOrEmpty();
        return actions.contains("*") || actions.contains(actionCode);
    }

    public void requireExecute(String actionCode) {
        if (!canExecute(actionCode)) {
            throw OntologyException.forbidden("无权执行 Action: " + actionCode);
        }
    }

    public boolean canReadField(String typeCode, String propKey) {
        if (!canReadType(typeCode)) {
            return false;
        }
        var fields = effectiveAcl().fieldsOrEmpty();
        if (fields.isEmpty()) {
            return true;
        }
        List<String> allowed = fields.get(typeCode);
        return allowed != null && (allowed.contains("*") || allowed.contains(propKey));
    }

    /**
     * 调用方请求的 fields 与 ACL 求交集（服务端裁剪）。
     *
     * @param requested 调用方请求的属性；为空表示"能看的都要"
     * @return 实际允许返回的属性定义
     */
    public List<PropertyDef> readableProperties(ObjectTypeDef type, List<String> requested) {
        List<PropertyDef> out = new ArrayList<>();
        List<String> requestedOrEmpty = requested == null ? List.of() : requested;
        for (PropertyDef p : type.propertiesOrEmpty()) {
            if (!canReadField(type.code(), p.key())) {
                continue;
            }
            if (requestedOrEmpty.isEmpty() || requestedOrEmpty.contains(p.key())) {
                out.add(p);
            }
        }
        return out;
    }
}
