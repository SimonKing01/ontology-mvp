package com.example.ontology.action;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计查询：谁、什么时候、对哪个对象、把哪个属性从什么改成了什么。
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "审计", description = "act_run / act_edit 查询")
public class AuditController {

    private final ReportService reportService;

    public AuditController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "按 runId 查执行记录与全部变更明细")
    public ActionDtos.RunDetail run(@PathVariable String runId) {
        return reportService.run(runId);
    }

    @GetMapping("/objects/{objectId}/edits")
    @Operation(summary = "某对象的变更历史（倒序）")
    public List<ActionDtos.EditView> editsOfObject(@PathVariable String objectId,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return reportService.editsOfObject(objectId, limit);
    }

    @GetMapping("/actions/{code}/runs")
    @Operation(summary = "某 Action 的最近执行记录")
    public List<ActionDtos.RunView> runsOfAction(@PathVariable String code,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return reportService.runsOfAction(code, limit);
    }
}
