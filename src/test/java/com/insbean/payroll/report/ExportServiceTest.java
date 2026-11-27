package com.insbean.payroll.report;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.record.RecordService;
import com.insbean.payroll.record.dto.ChangesetChange;
import com.insbean.payroll.record.dto.ChangesetItem;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.report.dto.ExportRequest;
import com.insbean.payroll.report.dto.ReportExportParams;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导出（Task 20）验收：权限、审计、金额数值单元格、列快照、工资条不含敏感字段。
 */
class ExportServiceTest extends AbstractDbTest {

    @Autowired
    private ExportService exportService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private RecordService recordService;

    @Autowired
    private JdbcTemplate jdbc;

    private long periodId;

    @BeforeEach
    void setUpPayroll() {
        Long empId = employeeService.create(new EmployeeSaveReq(
                null, "E001", "张三", 1, "ID_CARD", "110101199001011234", null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 3, 1),
                new BigDecimal("8000"), "初始", List.of()));

        periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        Long recordId = jdbc.queryForObject(
                "SELECT id FROM pay_record WHERE period_id = ?", Long.class, periodId);
        recordService.applyChangeset(periodId, new ChangesetReq(List.of(
                new ChangesetChange(recordId, 0, List.of(new ChangesetItem("PERF_BONUS", "2000.00"))))));
        periodService.lock(periodId, 2L);
    }

    @Test
    void 无导出权限时抛403且不写审计() {
        login(Set.of("payroll:record:view"));
        assertThatThrownBy(() -> exportService.export(monthly()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        assertThat(auditCount()).isZero();
    }

    @Test
    void 导出月度写审计且金额是数值不是文本() throws Exception {
        login(exportPerms());
        PreparedExport prepared = exportService.export(monthly());
        try {
            assertThat(prepared.rows()).isEqualTo(1);
            assertThat(prepared.filename()).startsWith("monthly-2026-11-").endsWith(".xlsx");

            try (InputStream in = Files.newInputStream(prepared.path());
                 XSSFWorkbook workbook = new XSSFWorkbook(in)) {
                Sheet sheet = workbook.getSheetAt(0);
                Row header = sheet.getRow(0);
                assertThat(header.getCell(4).getStringCellValue()).isEqualTo("应发合计");

                Row data = sheet.getRow(1);
                // 文本列必须写成字符串单元格
                assertThat(data.getCell(0).getCellType()).isEqualTo(CellType.STRING);
                assertThat(data.getCell(0).getStringCellValue()).isEqualTo("默认主体");

                Cell gross = data.getCell(4);
                assertThat(gross.getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(gross.getNumericCellValue()).isEqualTo(10600.00);
                assertThat(gross.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            }
        } finally {
            Files.deleteIfExists(prepared.path());
        }

        assertThat(auditCount()).isEqualTo(1);
        String audit = jdbc.queryForObject(
                "SELECT CONCAT(action, ':', new_value) FROM pay_audit_log WHERE biz_type = 'EXPORT'",
                String.class);
        assertThat(audit).contains("EXPORT", "type=MONTHLY", "rows=1");
    }

    @Test
    void 期间工资表列取快照_含已停用但有明细的科目() throws Exception {
        // 生成后再停用科目：当前启用科目里没有它，但快照与明细里仍应保留
        jdbc.update("UPDATE pay_item SET status = 0 WHERE item_code = 'PERF_BONUS'");

        login(exportPerms());
        PreparedExport prepared = exportService.export(new ExportRequest(
                "PERIOD_SHEET", new ReportExportParams("2026-11", null, null, null, null, null), "XLSX"));
        try {
            try (InputStream in = Files.newInputStream(prepared.path());
                 XSSFWorkbook workbook = new XSSFWorkbook(in)) {
                Sheet sheet = workbook.getSheetAt(0);
                List<String> headers = new ArrayList<>();
                sheet.getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
                assertThat(headers).contains("工号", "姓名", "部门", "绩效奖金", "应发合计");

                // 绩效奖金列应带出明细金额且为数值
                int perfIndex = headers.indexOf("绩效奖金");
                Cell perf = sheet.getRow(1).getCell(perfIndex);
                assertThat(perf.getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(perf.getNumericCellValue()).isEqualTo(2000.00);
            }
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void 工资条不含证件号手机号银行卡() throws Exception {
        login(exportPerms());
        PreparedExport prepared = exportService.export(new ExportRequest(
                "PAYSLIP", new ReportExportParams("2026-11", null, null, null, null, null), "XLSX"));
        try {
            try (InputStream in = Files.newInputStream(prepared.path());
                 XSSFWorkbook workbook = new XSSFWorkbook(in)) {
                List<String> headers = new ArrayList<>();
                workbook.getSheetAt(0).getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
                assertThat(headers).contains("工号", "姓名", "部门", "实发合计");
                assertThat(headers).noneMatch(h -> h.contains("证件") || h.contains("手机") || h.contains("银行卡"));
                // 工号是文本单元格，长工号不会被 Excel 转成科学计数法
                Cell empNo = workbook.getSheetAt(0).getRow(1).getCell(0);
                assertThat(empNo.getCellType()).isEqualTo(CellType.STRING);
                assertThat(empNo.getStringCellValue()).isEqualTo("E001");
            }
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    @Test
    void 只导出已锁定期间与数据范围内的数据() throws Exception {
        // SELF 范围只看本人；employeeId=999 没有记录 → 空报表
        login(Set.of("payroll:export", "payroll:record:view"));
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:export", "payroll:record:view"), "SELF", 999L));

        PreparedExport empty = exportService.export(monthly());
        try {
            try (InputStream in = Files.newInputStream(empty.path());
                 XSSFWorkbook workbook = new XSSFWorkbook(in)) {
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                        .isEqualTo("该期间没有已锁定数据");
            }
        } finally {
            Files.deleteIfExists(empty.path());
        }
        // 审计行数记业务数据行（0），不含说明行
        assertThat(jdbc.queryForObject("""
                SELECT new_value FROM pay_audit_log WHERE biz_type = 'EXPORT'
                 ORDER BY id DESC LIMIT 1
                """, String.class)).contains("rows=0");
    }

    @Test
    void 缺少必填参数返回400() {
        login(exportPerms());
        assertThatThrownBy(() -> exportService.export(new ExportRequest(
                "MONTHLY", new ReportExportParams(null, null, null, null, null, null), "XLSX")))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> exportService.export(new ExportRequest(
                "ANNUAL", new ReportExportParams(null, null, "EMP", null, null, null), "XLSX")))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> exportService.export(new ExportRequest(
                "ANNUAL", new ReportExportParams(null, "2026", null, null, null, null), "XLSX")))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> exportService.export(new ExportRequest(
                "PERIOD_SHEET", new ReportExportParams("2026-13", null, null, null, null, null), "XLSX")))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> exportService.export(new ExportRequest(
                "UNKNOWN", new ReportExportParams("2026-11", null, null, null, null, null), "XLSX")))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        // 任何一次失败都不得留下审计
        assertThat(auditCount()).isZero();
    }

    @Test
    void 编制中期间导出完整工资表返回409() {
        // 另建一个未锁定的期间并生成数据
        long december = periodService.create(
                new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(december, 1L);

        login(exportPerms());
        var params = new ReportExportParams("2026-12", null, null, null, null, null);
        assertThatThrownBy(() -> exportService.export(
                new ExportRequest("PERIOD_SHEET", params, "XLSX")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
        assertThatThrownBy(() -> exportService.export(
                new ExportRequest("PAYSLIP", params, "XLSX")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
        // 未提交，不应留下导出审计
        assertThat(auditCount()).isZero();
    }

    @Test
    void 报表空结果写入说明行且审计记录零行() throws Exception {
        login(exportPerms());
        PreparedExport prepared = exportService.export(new ExportRequest(
                "MONTHLY", new ReportExportParams("2026-12", null, null, null, null, null), "XLSX"));
        try {
            try (InputStream in = Files.newInputStream(prepared.path());
                 XSSFWorkbook workbook = new XSSFWorkbook(in)) {
                Row first = workbook.getSheetAt(0).getRow(1);
                // 空报表不能只给一个空表头，否则用户会误判"这个月没有工资"
                assertThat(first.getCell(0).getStringCellValue()).isEqualTo("该期间没有已锁定数据");
            }
        } finally {
            Files.deleteIfExists(prepared.path());
        }
        String audit = jdbc.queryForObject("""
                SELECT new_value FROM pay_audit_log WHERE biz_type = 'EXPORT'
                 ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(audit).contains("rows=0");
    }

    // ------------------------------------------------------------------ 工具

    private ExportRequest monthly() {
        return new ExportRequest("MONTHLY",
                new ReportExportParams("2026-11", null, null, null, null, null), "XLSX");
    }

    private void login(Set<String> perms) {
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT, perms, "ALL", null));
    }

    private static Set<String> exportPerms() {
        return Set.of("payroll:export", "payroll:record:view", "payroll:record:edit",
                "payroll:period:lock", "payroll:period:unlock");
    }

    private int auditCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_audit_log WHERE biz_type = 'EXPORT'", Integer.class);
        return count == null ? 0 : count;
    }
}
