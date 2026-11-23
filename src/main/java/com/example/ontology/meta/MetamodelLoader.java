package com.example.ontology.meta;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 ont_definition 里的 JSON 解析成类型化定义，并做全量语义校验。
 *
 * <p>解析与校验分开收集错误，一次返回全部问题，而不是改一个报一个。</p>
 */
@Component
public class MetamodelLoader {

    private final ObjectMapper mapper;

    public MetamodelLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param raw kind -> code -> payload JSON
     * @throws OntologyException METAMODEL_INVALID（details.errors 给出全部问题）
     */
    public MetamodelSnapshot parse(Map<String, Map<String, String>> raw) {
        List<String> errors = new ArrayList<>();
        Map<String, ObjectTypeDef> types = new LinkedHashMap<>();
        Map<String, LinkTypeDef> links = new LinkedHashMap<>();
        Map<String, ActionTypeDef> actions = new LinkedHashMap<>();
        Map<String, Integer> revisions = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> kindEntry : raw.entrySet()) {
            String kind = kindEntry.getKey();
            for (Map.Entry<String, String> e : kindEntry.getValue().entrySet()) {
                String code = e.getKey();
                String payload = e.getValue();
                try {
                    switch (kind) {
                        case "OBJECT_TYPE" -> types.put(code, mapper.readValue(payload, ObjectTypeDef.class));
                        case "LINK_TYPE" -> links.put(code, mapper.readValue(payload, LinkTypeDef.class));
                        case "ACTION_TYPE" -> actions.put(code, mapper.readValue(payload, ActionTypeDef.class));
                        default -> errors.add("未知元模型种类: " + kind);
                    }
                } catch (Exception ex) {
                    errors.add(kind + "/" + code + " payload 无法解析: " + ex.getMessage());
                }
            }
        }

        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        List<String> semantic = MetamodelValidator.validate(types, links, actions);
        if (!semantic.isEmpty()) {
            throw invalid(semantic);
        }
        return new MetamodelSnapshot(types, links, actions, revisions);
    }

    private OntologyException invalid(List<String> errors) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errors", errors);
        return new OntologyException(ErrorCode.METAMODEL_INVALID,
                "元模型校验失败，共 " + errors.size() + " 处问题", details);
    }
}
