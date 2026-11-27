package com.insbean.payroll.report.dto;

import com.insbean.payroll.tax.dto.AnnualTaxRow;

import java.util.List;

/**
 * 年度个税核对响应：与 /reports/monthly、/reports/annual 保持同一结构
 * {@code { rows, excludedPeriods }}，前端可用同一套结构校验。
 *
 * @param excludedPeriods 该年度内"有核算明细但期间未锁定"的月份
 */
public record AnnualTaxReportResult(List<AnnualTaxRow> rows, List<String> excludedPeriods) {
}
