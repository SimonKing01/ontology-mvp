package com.example.ontology.meta;

import com.example.ontology.common.OntologyException;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元模型注册表：启动时加载 + 校验，之后作为内存只读快照服务所有读路径。
 *
 * <p><b>启动期校验（R7）</b>：如果某个 Action 试图修改 owner=source/derived 的属性，
 * 或者链接/属性/参数相互引用不上，这里会直接抛异常让应用启动失败，而不是运行期才发现。</p>
 */
@Component
public class MetamodelRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetamodelRegistry.class);

    private final OntDefinitionRepository repository;
    private final MetamodelLoader loader;

    private volatile MetamodelSnapshot snapshot = MetamodelSnapshot.empty();

    public MetamodelRegistry(OntDefinitionRepository repository, MetamodelLoader loader) {
        this.repository = repository;
        this.loader = loader;
    }

    @PostConstruct
    public void verifyOnStartup() {
        try {
            reload();
            log.info("元模型加载完成：objectTypes={}, linkTypes={}, actions={}",
                    snapshot.objectTypes().size(), snapshot.linkTypes().size(), snapshot.actions().size());
        } catch (OntologyException ex) {
            String detail = String.valueOf(ex.details().get("errors"));
            throw new IllegalStateException("元模型启动校验失败：" + ex.getMessage() + " " + detail, ex);
        }
    }

    /** 从数据库重建快照；任何不合法都会抛异常（不部分生效）。 */
    public synchronized void reload() {
        Map<String, Map<String, String>> raw = new LinkedHashMap<>();
        Map<String, Integer> revisions = new LinkedHashMap<>();
        for (OntDefinition d : repository.findAll()) {
            raw.computeIfAbsent(d.getKind(), k -> new LinkedHashMap<>()).put(d.getCode(), d.getPayload());
            revisions.put(d.getKind() + "/" + d.getCode(), d.getRevision());
        }
        MetamodelSnapshot parsed = loader.parse(raw);
        this.snapshot = new MetamodelSnapshot(parsed.objectTypes(), parsed.linkTypes(), parsed.actions(), revisions);
    }

    public void apply(MetamodelSnapshot newSnapshot) {
        this.snapshot = newSnapshot;
    }

    public MetamodelSnapshot snapshot() {
        return snapshot;
    }

    public ObjectTypeDef objectType(String code) {
        ObjectTypeDef def = snapshot.objectTypes().get(code);
        if (def == null) {
            throw OntologyException.definitionNotFound(DefinitionKind.OBJECT_TYPE.dbValue(), code);
        }
        return def;
    }

    public ObjectTypeDef findObjectType(String code) {
        return snapshot.objectTypes().get(code);
    }

    public Collection<ObjectTypeDef> objectTypes() {
        return snapshot.objectTypes().values();
    }

    public LinkTypeDef linkType(String code) {
        LinkTypeDef def = snapshot.linkTypes().get(code);
        if (def == null) {
            throw OntologyException.definitionNotFound(DefinitionKind.LINK_TYPE.dbValue(), code);
        }
        return def;
    }

    public LinkTypeDef findLinkType(String code) {
        return snapshot.linkTypes().get(code);
    }

    public List<LinkTypeDef> linkTypes() {
        return List.copyOf(snapshot.linkTypes().values());
    }

    public ActionTypeDef action(String code) {
        ActionTypeDef def = snapshot.actions().get(code);
        if (def == null) {
            throw new OntologyException(com.example.ontology.common.ErrorCode.ACTION_NOT_FOUND,
                    "Action 定义不存在: " + code, Map.of("code", code));
        }
        return def;
    }

    public List<ActionTypeDef> actions() {
        return List.copyOf(snapshot.actions().values());
    }

    public int revision(DefinitionKind kind, String code) {
        return snapshot.revision(kind, code);
    }
}
