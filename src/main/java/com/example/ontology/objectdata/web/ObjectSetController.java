package com.example.ontology.objectdata.web;

import com.example.ontology.objectdata.query.ObjectSetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对象集：把一次查询的条件保存下来，复用 / 共享 / 引用。
 */
@RestController
@RequestMapping("/api/object-sets")
@Tag(name = "对象集", description = "查询条件的持久化定义（不是结果快照）")
public class ObjectSetController {

    private final ObjectSetService service;

    public ObjectSetController(ObjectSetService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "我可见的对象集：自己的 + 别人共享的")
    public List<ObjectSetService.Definition> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    @Operation(summary = "保存一个对象集（存条件，不存结果）")
    public ObjectSetService.Definition create(@RequestBody ObjectSetService.CreateRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看对象集定义")
    public ObjectSetService.Definition get(@PathVariable String id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    @Operation(summary = "删除对象集（只有创建者可以删）")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
