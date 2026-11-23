package com.example.ontology.meta;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元模型定义服务：版本化写入（revision + 历史表）。
 *
 * <p>写入顺序：先把"候选快照"（现有定义 + 本次修改）整体校验一遍，
 * 校验通过才落库 —— 保证任何时刻磁盘上的元模型都是自洽的，不存在"改一半"的中间态。</p>
 */
@Service
public class DefinitionService {

    private final OntDefinitionRepository repository;
    private final OntDefinitionHistoryRepository historyRepository;
    private final MetamodelLoader loader;
    private final MetamodelRegistry registry;
    private final ObjectMapper mapper;

    public DefinitionService(OntDefinitionRepository repository,
                             OntDefinitionHistoryRepository historyRepository,
                             MetamodelLoader loader,
                             MetamodelRegistry registry,
                             ObjectMapper mapper) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.loader = loader;
        this.registry = registry;
        this.mapper = mapper;
    }

    @Transactional
    public DefinitionView create(DefinitionKind kind, JsonNode body) {
        String code = requireCode(body);
        repository.findByKindAndCode(kind.dbValue(), code).ifPresent(d -> {
            throw OntologyException.conflict("定义已存在: " + kind.dbValue() + "/" + code,
                    Map.of("kind", kind.dbValue(), "code", code, "revision", d.getRevision()));
        });
        return persist(kind, code, body, null);
    }

    @Transactional
    public DefinitionView update(DefinitionKind kind, String code, JsonNode body) {
        OntDefinition existing = repository.findByKindAndCode(kind.dbValue(), code)
                .orElseThrow(() -> OntologyException.definitionNotFound(kind.dbValue(), code));
        String bodyCode = requireCode(body);
        if (!code.equals(bodyCode)) {
            throw OntologyException.badRequest("code 创建后不可修改：路径为 " + code + "，请求体为 " + bodyCode);
        }
        return persist(kind, code, body, existing);
    }

    private DefinitionView persist(DefinitionKind kind, String code, JsonNode body, OntDefinition existing) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw OntologyException.badRequest("请求体无法序列化: " + ex.getMessage());
        }

        Map<String, Map<String, String>> raw = new LinkedHashMap<>();
        Map<String, Integer> revisions = new LinkedHashMap<>();
        for (OntDefinition d : repository.findAll()) {
            raw.computeIfAbsent(d.getKind(), k -> new LinkedHashMap<>()).put(d.getCode(), d.getPayload());
            revisions.put(d.getKind() + "/" + d.getCode(), d.getRevision());
        }
        // 候选快照：把本次修改放进全量定义里一起校验
        raw.computeIfAbsent(kind.dbValue(), k -> new LinkedHashMap<>()).put(code, payload);

        MetamodelSnapshot parsed = loader.parse(raw);

        int newRevision = existing == null ? 1 : existing.getRevision() + 1;
        revisions.put(kind.dbValue() + "/" + code, newRevision);
        MetamodelSnapshot candidate = new MetamodelSnapshot(
                parsed.objectTypes(), parsed.linkTypes(), parsed.actions(), revisions);

        Instant now = Instant.now();
        if (existing == null) {
            repository.save(OntDefinition.create(kind.dbValue(), code, 1, payload, now));
        } else {
            // 旧版本先落历史，再更新当前版本（R8：元模型可版本化，存量数据可迁移）
            historyRepository.save(OntDefinitionHistory.of(
                    kind.dbValue(), code, existing.getRevision(), existing.getPayload(), now));
            existing.updateTo(newRevision, payload, now);
        }
        repository.flush();

        applyAfterCommit(candidate);
        return new DefinitionView(kind.dbValue(), code, newRevision, body, now);
    }

    /** 内存快照只在事务提交后替换：未提交的定义不能对外可见。 */
    private void applyAfterCommit(MetamodelSnapshot candidate) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registry.apply(candidate);
                }
            });
        } else {
            registry.apply(candidate);
        }
    }

    @Transactional(readOnly = true)
    public DefinitionView get(DefinitionKind kind, String code, Integer revision) {
        OntDefinition current = repository.findByKindAndCode(kind.dbValue(), code)
                .orElseThrow(() -> OntologyException.definitionNotFound(kind.dbValue(), code));
        if (revision == null || revision == current.getRevision()) {
            return new DefinitionView(kind.dbValue(), code, current.getRevision(), read(current.getPayload()),
                    current.getUpdatedAt());
        }
        OntDefinitionHistory h = historyRepository
                .findByKindAndCodeAndRevision(kind.dbValue(), code, revision)
                .orElseThrow(() -> new OntologyException(ErrorCode.DEFINITION_NOT_FOUND,
                        "历史版本不存在: " + code + "@" + revision,
                        Map.of("kind", kind.dbValue(), "code", code, "revision", revision)));
        return new DefinitionView(kind.dbValue(), code, h.getRevision(), read(h.getPayload()), h.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<DefinitionView> list(DefinitionKind kind) {
        List<DefinitionView> out = new ArrayList<>();
        for (OntDefinition d : repository.findByKindOrderByCodeAsc(kind.dbValue())) {
            out.add(new DefinitionView(d.getKind(), d.getCode(), d.getRevision(), read(d.getPayload()), d.getUpdatedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<RevisionView> history(DefinitionKind kind, String code) {
        List<RevisionView> out = new ArrayList<>();
        for (OntDefinitionHistory h : historyRepository.findByKindAndCodeOrderByRevisionDesc(kind.dbValue(), code)) {
            out.add(new RevisionView(h.getRevision(), read(h.getPayload()), h.getCreatedAt()));
        }
        return out;
    }

    private JsonNode read(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new OntologyException(ErrorCode.INTERNAL, "元模型 payload 损坏: " + ex.getMessage());
        }
    }

    private String requireCode(JsonNode body) {
        if (body == null || !body.hasNonNull("code") || body.get("code").asText().isBlank()) {
            throw OntologyException.badRequest("请求体必须包含非空的 code");
        }
        return body.get("code").asText();
    }
}
