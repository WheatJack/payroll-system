package com.insbean.payroll.report.dto;

/** 契约 components.schemas.ExportRequest。 */
public record ExportRequest(String reportType, ReportExportParams params, String format) {
}
