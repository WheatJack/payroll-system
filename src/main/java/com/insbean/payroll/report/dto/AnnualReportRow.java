package com.insbean.payroll.report.dto;

import java.math.BigDecimal;

/**
 * 契约 components.schemas.AnnualReportRow。
 *
 * <p>按人分组时 employeeId/empNo/empName 有值，且 {@code employeeCount} 为 null、
 * {@code monthCount} 为该员工有工资的月份数（按人分组时"人数"恒为 1，没有意义）；
 * 按部门分组时 deptId/deptName 有值，{@code employeeCount} 为组内去重人数，
 * {@code monthCount} 为 null。
 */
public record AnnualReportRow(
        String year,
        String groupBy,
        Long deptId,
        String deptName,
        Long employeeId,
        String empNo,
        String empName,
        Integer employeeCount,
        Integer monthCount,
        BigDecimal grossAmount,
        BigDecimal deductionAmount,
        BigDecimal netAmount
) {
}
