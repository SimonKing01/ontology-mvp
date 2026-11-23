package com.example.ontology.objectdata.web;

import com.example.ontology.objectdata.query.LinkQueryService;
import com.example.ontology.objectdata.query.TraversalService;
import com.example.ontology.objectdata.query.ObjectQueryDtos;
import com.example.ontology.objectdata.query.ObjectQueryService;
import com.example.ontology.common.PageResult;
import com.example.ontology.objectdata.query.ObjectQueryDtos.CountRequest;
import com.example.ontology.objectdata.query.ObjectQueryDtos.LinkItem;
import com.example.ontology.objectdata.query.ObjectQueryDtos.ObjectItem;
import com.example.ontology.objectdata.query.ObjectQueryDtos.QueryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对象读接口。
 *
 * <p>刻意<b>没有</b> PUT/PATCH/DELETE /api/objects/** —— 所有写入必须经过 Action（R3）。</p>
 */
@RestController
@RequestMapping("/api/objects")
@Tag(name = "对象查询", description = "属性过滤下推 SQL、keyset 分页、一跳链接；只读，写操作走 Action")
public class ObjectController {

    private final ObjectQueryService queryService;
    private final LinkQueryService linkQueryService;
    private final TraversalService traversalService;

    public ObjectController(ObjectQueryService queryService, LinkQueryService linkQueryService,
                            TraversalService traversalService) {
        this.queryService = queryService;
        this.linkQueryService = linkQueryService;
        this.traversalService = traversalService;
    }

    @PostMapping("/query")
    @Operation(summary = "查询对象（过滤条件翻译成 obj_prop 的 EXISTS 子查询，keyset 分页，不返回 total）")
    public PageResult<ObjectItem> query(@RequestBody QueryRequest request) {
        return queryService.query(request);
    }

    @PostMapping("/count")
    @Operation(summary = "计数（单独接口，主查询不返回 total）")
    public Map<String, Object> count(@RequestBody CountRequest request) {
        return Map.of("typeCode", request.typeCode(), "count", queryService.count(request));
    }

    @GetMapping("/{objectId}")
    @Operation(summary = "按对象 ID 读取（属性级 ACL 在服务端裁剪）")
    public ObjectItem get(@PathVariable String objectId,
                          @RequestParam(required = false) List<String> fields) {
        return queryService.get(objectId, fields);
    }

    @GetMapping("/{objectId}/links")
    @Operation(summary = "一跳链接遍历（OUT|IN，keyset 分页，只做一跳）")
    public PageResult<LinkItem> links(@PathVariable String objectId,
                                      @RequestParam(required = false) String linkType,
                                      @RequestParam(required = false, defaultValue = "OUT") String direction,
                                      @RequestParam(required = false) Integer pageSize,
                                      @RequestParam(required = false) String cursor) {
        return linkQueryService.links(objectId, linkType, direction, pageSize, cursor);
    }

    @PostMapping("/{objectId}/traverse")
    @Operation(summary = "多跳遍历：按链接路径走 N 跳（深度≤3，每跳≤100，超出截断）")
    public TraversalService.Result traverse(@PathVariable String objectId,
                                            @RequestBody TraversalService.Request request) {
        return traversalService.traverse(objectId, request.path(), request.limit());
    }
}
