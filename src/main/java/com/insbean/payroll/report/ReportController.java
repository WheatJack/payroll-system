package com.insbean.payroll.report;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.report.dto.AnnualReportRow;
import com.insbean.payroll.report.dto.MonthlyReportRow;
import com.insbean.payroll.report.dto.ReportQuery;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/monthly")
    public Result<List<MonthlyReportRow>> monthly(@RequestParam String periodYm,
                                                  @RequestParam(required = false) Long companyId,
                                                  @RequestParam(required = false) Long deptId) {
        return Result.ok(reportService.monthly(ReportQuery.monthly(periodYm, companyId, deptId)));
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/annual")
    public Result<List<AnnualReportRow>> annual(@RequestParam String year,
                                                @RequestParam String groupBy,
                                                @RequestParam(required = false) Long companyId,
                                                @RequestParam(required = false) Long deptId) {
        return Result.ok(reportService.annual(ReportQuery.annual(year, groupBy, companyId, deptId)));
    }
}
