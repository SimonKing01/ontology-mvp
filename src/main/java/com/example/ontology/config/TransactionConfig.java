package com.example.ontology.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 两个事务管理器，边界清晰：
 *
 * <ul>
 *   <li>{@code transactionManager}（@Primary）：JPA 事务，只服务元模型 / 数据源配置的仓库方法；</li>
 *   <li>{@code jdbcTxManager}：纯 JDBC 事务，Action 引擎与采集显式指定它，
 *       保证 obj_* 与 act_* 五张表共用同一个物理连接。</li>
 * </ul>
 *
 * <p>之所以不让 Action 依赖 JPA 事务管理器，是因为那样需要依赖"JPA 实现顺带把连接暴露给
 * JdbcTemplate"的隐式行为；显式 JDBC 事务是可验证的。</p>
 */
@Configuration
public class TransactionConfig {

    public static final String JDBC_TX_MANAGER = "jdbcTxManager";

    @Bean("transactionManager")
    @Primary
    public PlatformTransactionManager jpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean(JDBC_TX_MANAGER)
    public DataSourceTransactionManager jdbcTxManager(DataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        manager.setNestedTransactionAllowed(false);
        return manager;
    }
}
