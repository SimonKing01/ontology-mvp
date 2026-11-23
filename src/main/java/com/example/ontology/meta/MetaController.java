package com.example.ontology.meta;

import com.example.ontology.common.OntologyException;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元模型接口：对象类型 / 链接类型 / Action 类型。
 *
 * <p>写入仅 ADMIN；读取需要登录。code 创建后不可修改，修改一律走 PUT（revision + 1）。</p>
 */
@RestController
@RequestMapping("/api/meta")
@Tag(name = "元模型", description = "对象类型 / 链接类型 / Action 类型的版本化定义")
public class MetaController {

    private final DefinitionService service;
    private final MetamodelRegistry registry;

    public MetaController(DefinitionService service, MetamodelRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // 对象类型
    // ------------------------------------------------------------------
    @PostMapping("/object-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "创建对象类型（对象类型 = 数据源映射，不是实体类）")
    public DefinitionView createObjectType(@RequestBody JsonNode body) {
        return service.create(DefinitionKind.OBJECT_TYPE, body);
    }

    @PutMapping("/object-types/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "修改对象类型（revision + 1，旧版本进历史表）")
    public DefinitionView updateObjectType(@PathVariable String code, @RequestBody JsonNode body) {
        return service.update(DefinitionKind.OBJECT_TYPE, code, body);
    }

    @GetMapping("/object-types")
    @Operation(summary = "列出对象类型（当前版本）")
    public List<DefinitionView> listObjectTypes() {
        return service.list(DefinitionKind.OBJECT_TYPE);
    }

    @GetMapping("/object-types/{code}")
    @Operation(summary = "获取对象类型；revision 指定历史版本")
    public DefinitionView getObjectType(@PathVariable String code,
                                        @RequestParam(required = false) Integer revision) {
        return service.get(DefinitionKind.OBJECT_TYPE, code, revision);
    }

    @GetMapping("/object-types/{code}/history")
    @Operation(summary = "对象类型的历史版本列表")
    public List<RevisionView> objectTypeHistory(@PathVariable String code) {
        return service.history(DefinitionKind.OBJECT_TYPE, code);
    }

    // ------------------------------------------------------------------
    // 链接类型
    // ------------------------------------------------------------------
    @PostMapping("/link-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "创建链接类型（fromField/toField 是两侧源列，采集时 JOIN 物化）")
    public DefinitionView createLinkType(@RequestBody JsonNode body) {
        return service.create(DefinitionKind.LINK_TYPE, body);
    }

    @PutMapping("/link-types/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "修改链接类型")
    public DefinitionView updateLinkType(@PathVariable String code, @RequestBody JsonNode body) {
        return service.update(DefinitionKind.LINK_TYPE, code, body);
    }

    @GetMapping("/link-types")
    @Operation(summary = "列出链接类型")
    public List<DefinitionView> listLinkTypes() {
        return service.list(DefinitionKind.LINK_TYPE);
    }

    @GetMapping("/link-types/{code}")
    @Operation(summary = "获取链接类型")
    public DefinitionView getLinkType(@PathVariable String code,
                                      @RequestParam(required = false) Integer revision) {
        return service.get(DefinitionKind.LINK_TYPE, code, revision);
    }

    @GetMapping("/link-types/{code}/history")
    @Operation(summary = "链接类型的历史版本列表")
    public List<RevisionView> linkTypeHistory(@PathVariable String code) {
        return service.history(DefinitionKind.LINK_TYPE, code);
    }

    // ------------------------------------------------------------------
    // Action 类型
    // ------------------------------------------------------------------
    @PostMapping("/action-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "创建 Action 类型（写 ontology 属性会被启动期校验拒绝）")
    public DefinitionView createActionType(@RequestBody JsonNode body) {
        return service.create(DefinitionKind.ACTION_TYPE, body);
    }

    @PutMapping("/action-types/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "修改 Action 类型")
    public DefinitionView updateActionType(@PathVariable String code, @RequestBody JsonNode body) {
        return service.update(DefinitionKind.ACTION_TYPE, code, body);
    }

    @GetMapping("/action-types")
    @Operation(summary = "列出 Action 类型")
    public List<DefinitionView> listActionTypes() {
        return service.list(DefinitionKind.ACTION_TYPE);
    }

    @GetMapping("/action-types/{code}")
    @Operation(summary = "获取 Action 类型")
    public DefinitionView getActionType(@PathVariable String code,
                                        @RequestParam(required = false) Integer revision) {
        return service.get(DefinitionKind.ACTION_TYPE, code, revision);
    }

    @GetMapping("/action-types/{code}/history")
    @Operation(summary = "Action 类型的历史版本列表")
    public List<RevisionView> actionTypeHistory(@PathVariable String code) {
        return service.history(DefinitionKind.ACTION_TYPE, code);
    }

    // ------------------------------------------------------------------
    // 校验 / 重载
    // ------------------------------------------------------------------
    @GetMapping("/validate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "重新校验当前元模型（返回全部错误，不改变运行中快照）")
    public Map<String, Object> validate() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            registry.reload();
            body.put("valid", true);
            body.put("errors", List.of());
        } catch (OntologyException ex) {
            body.put("valid", false);
            body.put("errors", ex.details().getOrDefault("errors", List.of(ex.getMessage())));
        }
        body.put("objectTypes", registry.snapshot().objectTypes().size());
        body.put("linkTypes", registry.snapshot().linkTypes().size());
        body.put("actions", registry.snapshot().actions().size());
        return body;
    }
}
