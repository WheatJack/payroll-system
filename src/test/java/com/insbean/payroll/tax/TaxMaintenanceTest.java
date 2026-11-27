package com.insbean.payroll.tax;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.report.ExportService;
import com.insbean.payroll.report.PreparedExport;
import com.insbean.payroll.report.dto.ExportRequest;
import com.insbean.payroll.report.dto.ReportExportParams;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import com.insbean.payroll.tax.dto.AnnualTaxRow;
import com.insbean.payroll.tax.dto.InsuranceParamDTO;
import com.insbean.payroll.tax.dto.SocialInsuranceDTO;
import com.insbean.payroll.tax.dto.SocialInsuranceSaveReq;
import com.insbean.payroll.tax.dto.SpecialDeductionSaveReq;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/** 参保/专项扣除维护接口、年度个税核对报表与导出。 */
class TaxMaintenanceTest extends AbstractDbTest {

    @Autowired
    private SocialInsuranceService socialInsuranceService;

    @Autowired
    private SpecialDeductionService specialDeductionService;

    @Autowired
    private AnnualTaxReportService annualTaxReportService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private ExportService exportService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 录入参保信息后重新生成社保公积金不再为零() {
        Long empId = employee("E200", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-09"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        assertThat(itemAmount(periodId, "E200", "SOCIAL_INS")).isEqualByComparingTo("0.00");

        // 参保：北京、基数自动（无历史 → 当月应发 8600，在 6821–35283 内）
        socialInsuranceService.create(empId,
                new SocialInsuranceSaveReq("BJ", null, null, LocalDate.of(2025, 1, 1)));

        generateService.startGenerate(periodId, 1L);

        assertThat(itemAmount(periodId, "E200", "SOCIAL_INS")).isEqualByComparingTo("906.00");
        assertThat(itemAmount(periodId, "E200", "HOUSING_FUND")).isEqualByComparingTo("1032.00");
        // 应发 8600 − 5000 − 906 − 1032 = 1662 → 税 49.86（不再是 108）
        assertThat(itemAmount(periodId, "E200", "TAX")).isEqualByComparingTo("49.86");
    }

    @Test
    void 录入专项附加扣除后个税下降() {
        Long empId = employee("E201", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-09"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        assertThat(itemAmount(periodId, "E201", "TAX")).isEqualByComparingTo("108.00");

        specialDeductionService.save(empId, new SpecialDeductionSaveReq(
                "CHILD_EDU", new BigDecimal("2000"), LocalDate.of(2026, 1, 1), null, "子女教育"));

        generateService.startGenerate(periodId, 1L);

        // 8600 − 5000 − 2000 = 1600 → 48
        assertThat(itemAmount(periodId, "E201", "TAX")).isEqualByComparingTo("48.00");
        List<?> timeline = specialDeductionService.list(empId);
        assertThat(timeline).hasSize(1);
    }

    @Test
    void 年度核对报表与逐月生成结果一致() {
        Long empId = employee("E300", LocalDate.of(2021, 1, 1));
        profile(empId, "29500", LocalDate.of(2021, 1, 1));
        for (String ym : List.of("2026-01", "2026-02", "2026-03")) {
            long periodId = periodService.create(
                    new PeriodCreateReq(null, ym), TestDb.COMPANY_DEFAULT).id();
            generateService.startGenerate(periodId, 1L);
            periodService.lock(periodId, 2L);
        }

        List<AnnualTaxRow> rows = annualTaxReportService.annualTax("2026", null, null);
        assertThat(rows).hasSize(1);
        AnnualTaxRow row = rows.get(0);
        assertThat(row.empNo()).isEqualTo("E300");
        assertThat(row.paidMonths()).isEqualTo(3);
        assertThat(row.cumIncome()).isEqualByComparingTo("90000.00");
        assertThat(row.cumBasicDeduction()).isEqualByComparingTo("15000.00");
        assertThat(row.cumSocial()).isEqualByComparingTo("0.00");
        assertThat(row.cumFund()).isEqualByComparingTo("0.00");
        assertThat(row.cumSpecial()).isEqualByComparingTo("0.00");
        assertThat(row.taxable()).isEqualByComparingTo("75000.00");
        assertThat(row.cumTax()).isEqualByComparingTo("4980.00");
        assertThat(row.lastTax()).isEqualByComparingTo("2500.00"); // 3 月那一期
        assertThat(row.effectiveRate()).isEqualByComparingTo("0.0553");

        // 与台账逐项相等（报表只是复算，不是第二个真源）
        assertThat(jdbc.queryForObject("""
                SELECT cum_tax FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026
                """, BigDecimal.class, empId)).isEqualByComparingTo(row.cumTax());

        // 数据范围：SELF 只能看到自己
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:record:view"), "SELF", empId));
        assertThat(annualTaxReportService.annualTax("2026", null, null)).hasSize(1);
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:record:view"), "SELF", 999L));
        assertThat(annualTaxReportService.annualTax("2026", null, null)).isEmpty();
    }

    @Test
    void 参保参数按城市返回() {
        List<InsuranceParamDTO> params = socialInsuranceService.insuranceParams("BJ");
        assertThat(params).extracting(InsuranceParamDTO::insuranceType)
                .containsExactlyInAnyOrder("PENSION", "MEDICAL", "UNEMPLOYMENT", "HOUSING_FUND");
        assertThatThrownBy(() -> socialInsuranceService.insuranceParams("NOPE"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);
    }

    @Test
    void 已锁定期间的参保信息不能修改() {
        Long empId = employee("E202", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));
        SocialInsuranceDTO created = socialInsuranceService.create(empId,
                new SocialInsuranceSaveReq("BJ", null, null, LocalDate.of(2025, 1, 1)));

        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-09"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        periodService.lock(periodId, 2L);

        assertThatThrownBy(() -> socialInsuranceService.update(created.id(),
                new SocialInsuranceSaveReq("BJ", new BigDecimal("9000"), null,
                        LocalDate.of(2026, 9, 1))))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void 年度个税核对支持导出() throws Exception {
        Long empId = employee("E400", LocalDate.of(2021, 1, 1));
        profile(empId, "29500", LocalDate.of(2021, 1, 1));
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-01"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        periodService.lock(periodId, 2L);

        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:export", "payroll:record:view"), "ALL", null));
        PreparedExport prepared = exportService.export(new ExportRequest(
                "ANNUAL_TAX",
                new ReportExportParams(null, "2026", null, null, null, null), "XLSX"));
        try (InputStream in = Files.newInputStream(prepared.path());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = new ArrayList<>();
            sheet.getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
            assertThat(headers).contains("工号", "累计收入", "应纳税所得额", "实际税负率");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("E400");
            // 实际税负率是数值（4 位格式），3% 档首月 750 / 30000 = 0.0250
            int rateIndex = headers.indexOf("实际税负率");
            assertThat(sheet.getRow(1).getCell(rateIndex).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(rateIndex).getNumericCellValue()).isEqualTo(0.0250);
        } finally {
            Files.deleteIfExists(prepared.path());
        }
    }

    // ------------------------------------------------------------------ 工具

    private Long employee(String empNo, LocalDate hireDate) {
        return employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD",
                "11010119900101" + Math.abs(empNo.hashCode() % 10000), null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT, hireDate, null, null, 1, null),
                TestDb.COMPANY_DEFAULT);
    }

    private void profile(Long employeeId, String salary, LocalDate from) {
        compProfileService.save(new CompProfileSaveReq(null, employeeId, from,
                new BigDecimal(salary), "初始", List.of()));
    }

    private BigDecimal itemAmount(long periodId, String empNo, String itemCode) {
        return jdbc.queryForObject("""
                SELECT i.amount FROM pay_record_item i
                  JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND r.emp_no = ? AND i.item_code = ?
                """, BigDecimal.class, periodId, empNo, itemCode);
    }
}
