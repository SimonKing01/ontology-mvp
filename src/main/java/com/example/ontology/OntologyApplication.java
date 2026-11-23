package com.example.ontology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 本体服务 MVP 启动类。
 *
 * <p>本服务只做两件事：</p>
 * <ol>
 *   <li>把上游数据映射成业务对象（读模型，查询下推到 SQL）；</li>
 *   <li>把写操作收敛到 Action 这一条唯一路径（事务 + 幂等 + 审计 + 乐观锁）。</li>
 * </ol>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OntologyApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntologyApplication.class, args);
    }
}
