package com.example.ontology.datasource;

import com.example.ontology.common.OntologyException;
import com.example.ontology.common.SqlIdent;
import com.example.ontology.config.TransactionConfig;
import com.example.ontology.meta.MetamodelRegistry;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.ObjectTypeStatus;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.meta.model.PropertyOwner;
import com.example.ontology.objectdata.ObjectRow;
import com.example.ontology.objectdata.ObjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量采集：把上游表的行映射成对象 + 属性 + 链接 + 派生值。
 *
 * <p><b>边界</b>：采集只写 owner=source 与 owner=derived 的属性，从不触碰 ontology-owned 状态。
 * 因此它不是"第二条写路径"—— 业务状态的写入口只有 Action。</p>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final MetamodelRegistry registry;
    private final MetaQueryRepository meta;
    private final SourceConnectionFactory connections;
    private final ObjectStore store;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public IngestService(MetamodelRegistry registry,
                         MetaQueryRepository meta,
                         SourceConnectionFactory connections,
                         ObjectStore store,
                         NamedParameterJdbcTemplate jdbc,
                         ObjectMapper mapper,
                         @org.springframework.beans.factory.annotation.Qualifier(TransactionConfig.JDBC_TX_MANAGER)
                         PlatformTransactionManager txManager) {
        this.registry = registry;
        this.meta = meta;
        this.connections = connections;
        this.store = store;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------
    // 进度：单实例采集时记录，供前端轮询（多实例部署需换成共享状态）
    // ------------------------------------------------------------------
    private final java.util.concurrent.atomic.AtomicLong processedRows = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalRows = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = false;
    private volatile long startedAt = 0L;
    private volatile String currentType = null;

    public Map<String, Object> progress() {
        long processed = processedRows.get();
        long total = totalRows.get();
        long elapsed = running && startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        double percent = total > 0 ? Math.min(100.0, processed * 100.0 / total) : 0.0;
        double rate = elapsed > 0 ? processed * 1000.0 / elapsed : 0.0;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", running);
        body.put("busy", ingesting.get());
        body.put("action", runningAction);
        body.put("processed", processed);
        body.put("total", total);
        body.put("percent", Math.round(percent * 10) / 10.0);
        body.put("currentType", currentType);
        body.put("elapsedMs", elapsed);
        body.put("rowsPerSecond", Math.round(rate));
        return body;
    }

    /** 估算一个类型的采集目标行数（受数据集 maxRows 限制），用于进度条分母。 */
    private long estimateTypeRows(String typeCode) {
        try {
            ObjectTypeDef type = registry.objectType(typeCode);
            MetaQueryRepository.DatasetMeta ds = meta.requireDataset(type.datasetId());
            MetaQueryRepository.SourceMeta source = meta.requireSource(ds.sourceId());
            if ("POSTGRES".equals(source.kind())) {
                JdbcTemplate template = connections.jdbcTemplate(source);
                String table = SqlIdent.qualify(ds.schemaName(), ds.tableName());
                Long n = template.queryForObject("select count(*) from " + table, Long.class);
                return Math.min(n == null ? 0L : n, ds.maxRows());
            }
            return ds.maxRows();
        } catch (RuntimeException ex) {
            log.warn("估算采集目标失败 type={}: {}", typeCode, ex.getMessage());
            return 0L;
        }
    }

    private long estimateAllRows() {
        long total = 0;
        for (ObjectTypeDef type : registry.objectTypes()) {
            if (type.statusOrDefault() == ObjectTypeStatus.DEPRECATED) {
                continue;
            }
            total += estimateTypeRows(type.code());
        }
        return total;
    }

    private void beginProgress(long total) {
        processedRows.set(0L);
        totalRows.set(total);
        startedAt = System.currentTimeMillis();
        currentType = null;
        running = true;
    }

    private void endProgress() {
        running = false;
        currentType = null;
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------
    public record IngestReport(String typeCode, String datasetId, int readRows, int created, int updated,
                               int skipped, int propsWritten) {
    }

    public record LinkReport(int linkTypes, int linksCreated) {
    }

    public record DerivedReport(int propsComputed, int rowsAffected) {
    }

    public record IngestAllReport(List<IngestReport> objectTypes, LinkReport links, DerivedReport derived) {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------
    /** 采集互斥：同一时刻只允许一个采集在跑，避免并发采集打满连接池与堆内存。 */
    private final java.util.concurrent.atomic.AtomicBoolean ingesting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String runningAction = null;

    private <T> T withIngestLock(String action, java.util.function.Supplier<T> body) {
        if (!ingesting.compareAndSet(false, true)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requestedAction", action);
            detail.put("runningAction", runningAction);
            detail.put("processed", processedRows.get());
            detail.put("total", totalRows.get());
            detail.put("currentType", currentType);
            throw OntologyException.conflict("已有采集任务正在执行，请等它结束（GET /api/ingest/progress 可查看进度）", detail);
        }
        runningAction = action;
        try {
            return body.get();
        } finally {
            ingesting.set(false);
            runningAction = null;
        }
    }

    public boolean busy() {
        return ingesting.get();
    }

    @Transactional(transactionManager = TransactionConfig.JDBC_TX_MANAGER)
    public IngestReport ingestObjectType(String typeCode) {
        return withIngestLock("按类型采集 " + typeCode, () -> {
            beginProgress(estimateTypeRows(typeCode));
            currentType = typeCode;
            try {
                return doIngestObjectType(typeCode);
            } finally {
                endProgress();
            }
        });
    }

    @Transactional(transactionManager = TransactionConfig.JDBC_TX_MANAGER)
    public LinkReport rebuildLinks() {
        return withIngestLock("重建链接", this::doRebuildLinks);
    }

    @Transactional(transactionManager = TransactionConfig.JDBC_TX_MANAGER)
    public DerivedReport recomputeDerived() {
        return withIngestLock("重算派生", this::doRecomputeDerived);
    }

    /** 对象 → 链接 → 派生，一个事务内完成，避免出现"有对象没链接"的中间态。 */
    public IngestAllReport ingestAll() {
        return withIngestLock("全量采集", () -> {
            beginProgress(estimateAllRows());
            try {
                return tx.execute(status -> {
                    List<IngestReport> reports = new ArrayList<>();
                    for (ObjectTypeDef type : registry.objectTypes()) {
                        currentType = type.code();
                        reports.add(doIngestObjectType(type.code()));
                    }
                    LinkReport links = doRebuildLinks();
                    DerivedReport derived = doRecomputeDerived();
                    return new IngestAllReport(reports, links, derived);
                });
            } finally {
                endProgress();
            }
        });
    }

    // ------------------------------------------------------------------
    // 对象与属性
    // ------------------------------------------------------------------
    private IngestReport doIngestObjectType(String typeCode) {
        ObjectTypeDef type = registry.objectType(typeCode);
        if (type.statusOrDefault() == ObjectTypeStatus.DEPRECATED) {
            throw OntologyException.badRequest("对象类型已 deprecated，禁止采集: " + typeCode);
        }
        MetaQueryRepository.DatasetMeta ds = meta.requireDataset(type.datasetId());
        MetaQueryRepository.SourceMeta source = meta.requireSource(ds.sourceId());

        List<Map<String, Object>> rows = readRows(source, ds);
        PropertyDef idProp = type.require(type.idField());

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int propsWritten = 0;

        for (Map<String, Object> row : rows) {
            Object keyRaw = resolvePath(row, idProp.sourcePath());
            String businessKey = keyRaw == null ? null : String.valueOf(keyRaw).trim();
            if (businessKey == null || businessKey.isEmpty()) {
                skipped++;
                continue;
            }
            String objectId = ObjectRow.idOf(type.code(), businessKey);
            String title = resolveTitle(type, row, businessKey);
            if (store.upsertIngestedObject(objectId, type.code(), businessKey, title)) {
                created++;
            } else {
                updated++;
            }
            for (PropertyDef prop : type.propertiesOrEmpty()) {
                if (prop.owner() == PropertyOwner.ONTOLOGY) {
                    // ontology-owned 状态由 Action 独占写入，采集不得覆盖
                    continue;
                }
                String expr = prop.derivedExpr() == null ? null : prop.derivedExpr().trim();
                Object raw;
                if (prop.owner() == PropertyOwner.SOURCE) {
                    raw = resolvePath(row, prop.sourcePath());
                } else if (expr != null && expr.startsWith("src:")) {
                    raw = resolvePath(row, expr.substring("src:".length()));
                } else {
                    // link:/count:/sum: 依赖链接，等链接物化后统一计算
                    continue;
                }
                propsWritten += writeProperty(objectId, prop, raw);
            }
            processedRows.incrementAndGet();
        }

        IngestReport report = new IngestReport(type.code(), type.datasetId(), rows.size(), created, updated,
                skipped, propsWritten);
        log.info("采集完成 {}", report);
        return report;
    }

    private int writeProperty(String objectId, PropertyDef prop, Object raw) {
        if (prop.isArray()) {
            store.clearProperty(objectId, prop.key());
            Collection<?> values;
            if (raw instanceof Collection<?> c) {
                values = c;
            } else if (raw == null) {
                values = List.of();
            } else {
                values = List.of(raw);
            }
            int n = 0;
            int ord = 0;
            for (Object item : values) {
                Object v = prop.type().coerce(item);
                if (v == null) {
                    continue;
                }
                store.setProperty(objectId, prop, ord++, v);
                n++;
            }
            return n;
        }
        Object value = prop.type().coerce(raw);
        if (value == null) {
            store.clearProperty(objectId, prop.key());
            return 0;
        }
        store.setProperty(objectId, prop, 0, value);
        return 1;
    }

    private String resolveTitle(ObjectTypeDef type, Map<String, Object> row, String fallback) {
        if (type.titleField() == null || type.titleField().isBlank()) {
            return fallback;
        }
        PropertyDef titleProp = type.find(type.titleField());
        if (titleProp == null || titleProp.sourcePath() == null) {
            return fallback;
        }
        Object v = resolvePath(row, titleProp.sourcePath());
        return v == null ? fallback : String.valueOf(v);
    }

    // ------------------------------------------------------------------
    // 链接物化
    // ------------------------------------------------------------------
    private LinkReport doRebuildLinks() {
        int linkTypes = 0;
        int linksCreated = 0;
        for (LinkTypeDef link : registry.linkTypes()) {
            ObjectTypeDef from = registry.objectType(link.fromTypeCode());
            ObjectTypeDef to = registry.objectType(link.toTypeCode());
            MetaQueryRepository.DatasetMeta fromDs = meta.requireDataset(from.datasetId());
            MetaQueryRepository.DatasetMeta toDs = meta.requireDataset(to.datasetId());
            MetaQueryRepository.SourceMeta fromSource = meta.requireSource(fromDs.sourceId());
            MetaQueryRepository.SourceMeta toSource = meta.requireSource(toDs.sourceId());
            if (!fromSource.id().equals(toSource.id())) {
                throw OntologyException.badRequest(
                        "MVP 不支持跨数据源链接: " + link.code()
                                + "（" + fromSource.name() + " -> " + toSource.name() + "）");
            }
            if (!"POSTGRES".equals(fromSource.kind())) {
                throw OntologyException.badRequest("链接物化只支持 POSTGRES 数据源: " + link.code());
            }

            String fromKeyColumn = plainColumn(from, from.idField());
            String toKeyColumn = plainColumn(to, to.idField());
            String fromTable = SqlIdent.qualify(fromDs.schemaName(), fromDs.tableName());
            String toTable = SqlIdent.qualify(toDs.schemaName(), toDs.tableName());
            String fromId = SqlIdent.quote(fromKeyColumn, "idField");
            String toId = SqlIdent.quote(toKeyColumn, "idField");
            String fromField = SqlIdent.quote(link.fromField(), "fromField");
            String toField = SqlIdent.quote(link.toField(), "toField");

            String sql = """
                    insert into obj_link (link_id, link_type, from_object_id, to_object_id, properties)
                    select cast(:lt as text) || ':' || f.%1$s::text || '->' || t.%2$s::text,
                           :lt,
                           cast(:fromPrefix as text) || f.%1$s::text,
                           cast(:toPrefix as text) || t.%2$s::text,
                           '{}'::jsonb
                    from %3$s f
                    join %4$s t on t.%5$s = f.%6$s
                    join obj_object fo on fo.object_id = cast(:fromPrefix as text) || f.%1$s::text
                    join obj_object tobj on tobj.object_id = cast(:toPrefix as text) || t.%2$s::text
                    on conflict (link_type, from_object_id, to_object_id) do nothing
                    """.formatted(fromId, toId, fromTable, toTable, toField, fromField);

            int n = jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("lt", link.code())
                    .addValue("fromPrefix", from.code() + ":")
                    .addValue("toPrefix", to.code() + ":"));
            linksCreated += n;
            linkTypes++;
            log.info("链接物化 {}：{} 条", link.code(), n);
        }
        return new LinkReport(linkTypes, linksCreated);
    }

    // ------------------------------------------------------------------
    // 派生值（依赖链接的部分）
    // ------------------------------------------------------------------
    private DerivedReport doRecomputeDerived() {
        int computed = 0;
        int rows = 0;
        for (ObjectTypeDef type : registry.objectTypes()) {
            for (PropertyDef prop : type.propertiesOrEmpty()) {
                if (prop.owner() != PropertyOwner.DERIVED) {
                    continue;
                }
                String expr = prop.derivedExpr() == null ? "" : prop.derivedExpr().trim();
                if (expr.isEmpty() || expr.startsWith("src:")) {
                    continue;
                }
                String kind = expr.substring(0, expr.indexOf(':'));
                String rest = expr.substring(expr.indexOf(':') + 1);
                String linkCode = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
                String srcProp = rest.contains(".") ? rest.substring(rest.indexOf('.') + 1) : null;
                LinkTypeDef link = registry.findLinkType(linkCode);
                if (link == null) {
                    throw OntologyException.badRequest("派生表达式引用了不存在的链接: " + expr);
                }
                boolean anchorIsFrom = type.code().equals(link.fromTypeCode());
                String anchor = anchorIsFrom ? "from_object_id" : "to_object_id";
                String other = anchorIsFrom ? "to_object_id" : "from_object_id";

                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("prop", prop.key())
                        .addValue("type", type.code())
                        .addValue("lt", linkCode)
                        .addValue("srcProp", srcProp);
                // 先清旧值：链接消失后派生值不能残留
                jdbc.update("""
                        delete from obj_prop where prop_key = :prop
                        and object_id in (select object_id from obj_object where type_code = :type)
                        """, params);

                String sql = switch (kind) {
                    case "link" -> """
                            insert into obj_prop (object_id, prop_key, ord, v_num, v_str, v_ts)
                            select x.anchor, :prop, 0, x.v_num, x.v_str, x.v_ts
                            from (
                                select distinct on (l.%1$s) l.%1$s as anchor, p.v_num, p.v_str, p.v_ts
                                from obj_link l
                                join obj_prop p on p.object_id = l.%2$s and p.prop_key = :srcProp and p.ord = 0
                                where l.link_type = :lt
                                order by l.%1$s, l.%2$s
                            ) x
                            on conflict (object_id, prop_key, ord) do update
                            set v_num = excluded.v_num, v_str = excluded.v_str, v_ts = excluded.v_ts
                            """.formatted(anchor, other);
                    case "count" -> """
                            insert into obj_prop (object_id, prop_key, ord, v_num)
                            select o.object_id, :prop, 0,
                                   (select count(*) from obj_link l where l.link_type = :lt and l.%1$s = o.object_id)
                            from obj_object o where o.type_code = :type
                            on conflict (object_id, prop_key, ord) do update set v_num = excluded.v_num
                            """.formatted(anchor);
                    case "sum" -> """
                            insert into obj_prop (object_id, prop_key, ord, v_num)
                            select o.object_id, :prop, 0, coalesce(sum(p.v_num), 0)
                            from obj_object o
                            left join obj_link l on l.%1$s = o.object_id and l.link_type = :lt
                            left join obj_prop p on p.object_id = l.%2$s and p.prop_key = :srcProp and p.ord = 0
                            where o.type_code = :type
                            group by o.object_id
                            on conflict (object_id, prop_key, ord) do update set v_num = excluded.v_num
                            """.formatted(anchor, other);
                    default -> throw OntologyException.badRequest("未知派生表达式: " + expr);
                };
                rows += jdbc.update(sql, params);
                computed++;
                log.info("派生计算 {}.{} = {} → {} 行", type.code(), prop.key(), expr, rows);
            }
        }
        return new DerivedReport(computed, rows);
    }

    // ------------------------------------------------------------------
    // 读取上游
    // ------------------------------------------------------------------
    private List<Map<String, Object>> readRows(MetaQueryRepository.SourceMeta source,
                                               MetaQueryRepository.DatasetMeta ds) {
        if ("POSTGRES".equals(source.kind())) {
            JdbcTemplate template = connections.jdbcTemplate(source);
            String table = SqlIdent.qualify(ds.schemaName(), ds.tableName());
            return template.queryForList("select * from " + table + " limit ?", ds.maxRows());
        }
        if ("CSV".equals(source.kind())) {
            Path base = Path.of(source.host()).toAbsolutePath().normalize();
            Path file = base.resolve(ds.tableName()).normalize();
            if (!file.startsWith(base)) {
                throw OntologyException.badRequest("非法 CSV 路径: " + ds.tableName());
            }
            if (!Files.exists(file)) {
                throw OntologyException.badRequest("CSV 文件不存在: " + file);
            }
            return CsvReader.read(file, ds.maxRows());
        }
        throw OntologyException.badRequest("不支持的数据源类型: " + source.kind());
    }

    private Object resolvePath(Map<String, Object> row, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = getIgnoreCase(row, parts[0]);
        for (int i = 1; i < parts.length; i++) {
            current = descend(current, parts[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object descend(Object current, String key) {
        try {
            if (current instanceof PGobject pg) {
                JsonNode node = mapper.readTree(pg.getValue());
                return node.has(key) ? mapper.convertValue(node.get(key), Object.class) : null;
            }
            if (current instanceof JsonNode node) {
                return node.has(key) ? mapper.convertValue(node.get(key), Object.class) : null;
            }
            if (current instanceof Map<?, ?> map) {
                return map.get(key);
            }
            if (current instanceof String s && s.stripLeading().startsWith("{")) {
                JsonNode node = mapper.readTree(s);
                return node.has(key) ? mapper.convertValue(node.get(key), Object.class) : null;
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private Object getIgnoreCase(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 链接物化要求 idField 直接对应一个源列（不支持嵌套路径）。 */
    private String plainColumn(ObjectTypeDef type, String propKey) {
        PropertyDef prop = type.require(propKey);
        String path = prop.sourcePath();
        if (path == null || path.isBlank() || path.contains(".")) {
            throw OntologyException.badRequest(
                    "链接物化要求 " + type.code() + "." + propKey + " 的 sourcePath 是单一列名");
        }
        return path;
    }

    /** 供 demo 输出：对象层规模快照。 */
    public Map<String, Object> layerStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("objects", jdbc.queryForObject("select count(*) from obj_object", Map.of(), Long.class));
        stats.put("properties", jdbc.queryForObject("select count(*) from obj_prop", Map.of(), Long.class));
        stats.put("links", jdbc.queryForObject("select count(*) from obj_link", Map.of(), Long.class));
        return stats;
    }
}
