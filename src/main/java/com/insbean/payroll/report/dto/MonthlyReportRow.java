package com.insbean.payroll.report.dto;

import java.math.BigDecimal;

/** 契约 components.schemas.MonthlyReportRow。employeeCount 为去重人数。 */
public record MonthlyReportRow(
        Long companyId,
        String periodYm,
        Long deptId,
        String deptName,
        Integer employeeCount,
        BigDecimal grossAmount,
        BigDecimal deductionAmount,
        BigDecimal netAmount
) {
}
