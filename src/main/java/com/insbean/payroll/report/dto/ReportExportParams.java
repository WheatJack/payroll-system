package com.insbean.payroll.report.dto;

import java.util.List;

/**
 * 契约 components.schemas.ReportExportParams。
 * 按 reportType 取用不同字段；缺必填参数一律 400。
 */
public record ReportExportParams(
        String periodYm,
        String year,
        String groupBy,
        Long companyId,
        Long deptId,
        List<Long> employeeIds
) {
}
