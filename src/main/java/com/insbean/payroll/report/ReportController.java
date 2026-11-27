package com.insbean.payroll.report;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.report.dto.ExportRequest;
import com.insbean.payroll.report.dto.ReportQuery;
import com.insbean.payroll.security.RequirePerm;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportService reportService;
    private final ExportService exportService;

    public ReportController(ReportService reportService, ExportService exportService) {
        this.reportService = reportService;
        this.exportService = exportService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/monthly")
    public Result<ReportService.MonthlyReportResult> monthly(@RequestParam String periodYm,
                                                  @RequestParam(required = false) Long companyId,
                                                  @RequestParam(required = false) Long deptId) {
        return Result.ok(reportService.monthly(ReportQuery.monthly(periodYm, companyId, deptId)));
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/annual")
    public Result<ReportService.AnnualReportResult> annual(@RequestParam String year,
                                                @RequestParam String groupBy,
                                                @RequestParam(required = false) Long companyId,
                                                @RequestParam(required = false) Long deptId) {
        return Result.ok(reportService.annual(ReportQuery.annual(year, groupBy, companyId, deptId)));
    }

    /**
     * 导出是同步二进制流，**不使用统一响应包**。
     *
     * <p>两段式：{@link ExportService#export} 先把文件完整写到临时文件并写审计，
     * 成功后才设置响应头并输出；准备阶段的任何异常仍由 GlobalExceptionHandler
     * 返回 JSON 错误包（此时响应尚未提交）。
     */
    @RequirePerm("payroll:export")
    @PostMapping("/export")
    public void export(@RequestBody ExportRequest request, HttpServletResponse response) throws IOException {
        PreparedExport prepared = exportService.export(request);
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(XLSX_CONTENT_TYPE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + prepared.filename() + "\"");
            response.setContentLengthLong(Files.size(prepared.path()));
            try (InputStream in = Files.newInputStream(prepared.path())) {
                in.transferTo(response.getOutputStream());
            }
            response.flushBuffer();
        } finally {
            // 临时文件可能含全量工资数据，必须随即删除
            Files.deleteIfExists(prepared.path());
        }
    }
}
