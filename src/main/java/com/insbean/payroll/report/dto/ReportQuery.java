package com.insbean.payroll.report.dto;

/**
 * 报表查询条件（契约 /reports/monthly、/reports/annual 的 query 参数）。
 *
 * @param periodYm 月度报表必填
 * @param year     年度报表必填
 * @param groupBy  EMP / DEPT（兼容 EMPLOYEE 写法）
 * @param companyId 可选；ALL 范围 + 按部门年度汇总时必填
 * @param deptId   可选，限定部门
 */
public record ReportQuery(
        String periodYm,
        String year,
        String groupBy,
        Long companyId,
        Long deptId
) {

    public static ReportQuery monthly(String periodYm, Long companyId, Long deptId) {
        return new ReportQuery(periodYm, null, null, companyId, deptId);
    }

    public static ReportQuery annual(String year, String groupBy, Long companyId, Long deptId) {
        return new ReportQuery(null, year, groupBy, companyId, deptId);
    }
}
