package com.example.ontology.action;

import com.example.ontology.common.PageResult;
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

/**
 * Action 执行接口 —— 这是本服务唯一的写入口。
 */
@RestController
@RequestMapping("/api/actions")
@Tag(name = "Action", description = "唯一写路径：事务 + 幂等 + 前置条件 + 乐观锁 + 审计")
public class ActionController {

    private final ActionExecutionService executionService;
    private final AuditService auditService;
    private final ReportService reportService;

    public ActionController(ActionExecutionService executionService, AuditService auditService,
                            ReportService reportService) {
        this.executionService = executionService;
        this.auditService = auditService;
        this.reportService = reportService;
    }

    @PostMapping("/{code}/execute")
    @Operation(summary = "执行 Action（幂等键相同则返回上次结果；版本冲突 409；前置条件失败 422）")
    public ActionDtos.ExecuteResponse execute(@PathVariable String code,
                                              @RequestBody(required = false) ActionDtos.ExecuteRequest request) {
        return executionService.execute(code, request);
    }

    @GetMapping("/{code}/runs")
    @Operation(summary = "某 Action 的最近执行记录（审计）")
    public List<ActionDtos.RunView> runs(@PathVariable String code,
                                         @RequestParam(defaultValue = "20") int limit) {
        return reportService.runsOfAction(code, limit);
    }
}
