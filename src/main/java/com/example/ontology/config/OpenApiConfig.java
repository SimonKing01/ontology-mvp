package com.example.ontology.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置（HTTP Basic 鉴权）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ontologyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("本体（Ontology）服务 MVP")
                        .version("0.1.0")
                        .description("""
                                业务对象的读模型 + 唯一写路径。
                                - 对象类型是数据源映射，不是实体类；
                                - 查询下推为 SQL，属性过滤不走内存；
                                - 所有写操作只能通过 Action（事务 + 幂等 + 乐观锁 + 审计）。
                                演示账号：admin/admin（ADMIN）、editor/editor（EDITOR）、viewer/viewer（VIEWER）。
                                """))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
