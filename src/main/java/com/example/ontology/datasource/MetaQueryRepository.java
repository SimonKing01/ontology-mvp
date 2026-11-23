package com.example.ontology.datasource;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 采集路径的元数据只读查询（纯 JDBC）。
 *
 * <p>为什么不直接用 JPA 仓库：采集跑在显式 JDBC 事务里，若在同一线程内再启动 JPA 事务，
 * 两个事务管理器会争抢同一个 DataSource 连接绑定（Already value for key ... bound to thread）。
 * 采集只需要最基本的只读字段，用 JDBC 读最干净。</p>
 */
@Component
public class MetaQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MetaQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DatasetMeta(String id, Long sourceId, String schemaName, String tableName, int maxRows) {
    }

    public record SourceMeta(Long id, String name, String kind, String host, Integer port,
                             String databaseName, String username, String passwordCipher) {
    }

    public Optional<DatasetMeta> dataset(String id) {
        List<DatasetMeta> rows = jdbc.query("""
                        select id, source_id, schema_name, table_name, max_rows from ds_dataset where id = :id
                        """,
                Map.of("id", id), (rs, i) -> new DatasetMeta(
                        rs.getString("id"), rs.getLong("source_id"), rs.getString("schema_name"),
                        rs.getString("table_name"), rs.getInt("max_rows")));
        return rows.stream().findFirst();
    }

    public Optional<SourceMeta> source(Long id) {
        List<SourceMeta> rows = jdbc.query("""
                        select id, name, kind, host, port, database_name, username, password_cipher
                        from ds_source where id = :id
                        """,
                Map.of("id", id), (rs, i) -> new SourceMeta(
                        rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("host"),
                        rs.getObject("port") == null ? null : rs.getInt("port"),
                        rs.getString("database_name"), rs.getString("username"),
                        rs.getString("password_cipher")));
        return rows.stream().findFirst();
    }

    public DatasetMeta requireDataset(String id) {
        return dataset(id).orElseThrow(() -> new OntologyException(ErrorCode.DATASET_NOT_FOUND,
                "数据集不存在: " + id, Map.of("datasetId", id)));
    }

    public SourceMeta requireSource(Long id) {
        return source(id).orElseThrow(() -> new OntologyException(ErrorCode.DATASET_NOT_FOUND,
                "数据源不存在: " + id, Map.of("sourceId", id)));
    }
}
