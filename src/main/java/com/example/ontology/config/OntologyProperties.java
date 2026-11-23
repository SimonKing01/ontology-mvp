package com.example.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 本体服务配置：展示时区、加密主密钥、角色 ACL。
 *
 * <p>ACL 是属性级裁剪的数据来源：{@code types} 控制类型可见性，{@code actions} 控制可执行 Action，
 * {@code fields} 控制属性可见性（空 map 表示该角色可见类型下全部属性）。</p>
 */
@ConfigurationProperties(prefix = "ontology")
public record OntologyProperties(
        String timezone,
        String cryptoKey,
        Map<String, RoleAcl> acl
) {

    public String timezoneOrDefault() {
        return timezone == null || timezone.isBlank() ? "UTC" : timezone;
    }

    public Map<String, RoleAcl> aclOrEmpty() {
        return acl == null ? Map.of() : acl;
    }

    public record RoleAcl(List<String> types, List<String> actions, Map<String, List<String>> fields) {

        public List<String> typesOrEmpty() {
            return types == null ? List.of() : types;
        }

        public List<String> actionsOrEmpty() {
            return actions == null ? List.of() : actions;
        }

        public Map<String, List<String>> fieldsOrEmpty() {
            return fields == null ? Map.of() : fields;
        }
    }
}
