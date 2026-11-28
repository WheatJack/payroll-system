package com.insbean.payroll.audit;

import com.insbean.payroll.audit.dto.AuditLogPageDTO;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 审计流水查询。
 *
 * <p>action 筛选项前端写死即可（CREATE/UPDATE/DELETE/LOCK/UNLOCK/EXPORT/VIEW/
 * VIEW_SENSITIVE/GRANT_REPLACE），不再单独提供枚举接口。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @RequirePerm("sys:audit:view")
    @GetMapping
    public Result<AuditLogPageDTO> list(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(auditQueryService.page(bizType, action, operator, start, end, current, size));
    }
}
