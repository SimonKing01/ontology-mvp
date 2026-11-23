package com.example.ontology.datasource;

import com.example.ontology.common.OntologyException;
import com.example.ontology.config.CryptoService;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按数据源维度懒加载只读连接池。
 *
 * <p>采集是唯一会直连上游的路径；查询与 Action 只碰本服务的 obj_* 表。</p>
 */
@Component
public class SourceConnectionFactory implements DisposableBean {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final CryptoService crypto;

    public SourceConnectionFactory(CryptoService crypto) {
        this.crypto = crypto;
    }

    public JdbcTemplate jdbcTemplate(MetaQueryRepository.SourceMeta source) {
        if (!"POSTGRES".equals(source.kind())) {
            throw OntologyException.badRequest("数据源 " + source.name() + " 不是 POSTGRES，无法建立连接");
        }
        HikariDataSource ds = pools.computeIfAbsent(source.id(), id -> {
            HikariDataSource hikari = new HikariDataSource();
            hikari.setPoolName("src-" + id);
            hikari.setJdbcUrl("jdbc:postgresql://" + source.host() + ":" + source.port()
                    + "/" + source.databaseName());
            hikari.setUsername(source.username());
            hikari.setPassword(crypto.decrypt(source.passwordCipher()));
            hikari.setMaximumPoolSize(2);
            hikari.setMinimumIdle(0);
            hikari.setReadOnly(true);
            hikari.setConnectionTimeout(5000);
            return hikari;
        });
        return new JdbcTemplate(ds);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
