package com.insbean.payroll.tax;

import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个税与社保：单月、累计预扣、基数上下限、专项附加扣除、锁定/解锁可逆、跨年清零。
 */
class TaxServiceTest extends AbstractDbTest {

    @Autowired
    private TaxService taxService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 单月个税_应发一万无扣除为150() {
        // 10000 - 5000 = 5000 → 3% → 150
        assertThat(taxService.computeTax(1, new BigDecimal("10000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void 累计预扣_第三个月进入10percent档() {
        // 连续三个月应发 30000：应纳税所得额依次 25000 / 50000 / 75000
        BigDecimal month1 = taxService.computeTax(1, new BigDecimal("30000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(month1).isEqualByComparingTo("750.00"); // 25000×3%

        BigDecimal cumTax = month1;
        BigDecimal month2 = taxService.computeTax(2, new BigDecimal("60000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cumTax);
        assertThat(month2).isEqualByComparingTo("1730.00"); // 50000×10%−2520−750
        cumTax = cumTax.add(month2);

        BigDecimal month3 = taxService.computeTax(3, new BigDecimal("90000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cumTax);
        assertThat(month3).isEqualByComparingTo("2500.00"); // 75000×10%−2520−2480
        // 高收入累计后显著高于"单月 × 3%"
        assertThat(month3).isGreaterThan(new BigDecimal("900.00"));
    }

    @Test
    void 专项附加扣除降低应纳税所得额() {
        // 10000 - 5000 - 2000 = 3000 → 3% → 90
        assertThat(taxService.computeTax(1, new BigDecimal("10000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("2000"), BigDecimal.ZERO))
                .isEqualByComparingTo("90.00");
    }

    @Test
    void 应发低于起征点个税为零() {
        assertThat(taxService.computeTax(1, new BigDecimal("4000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void 社保基数受上下限约束() {
        Long empId = employee("E001", LocalDate.of(2020, 3, 1));
        // 北京参数：下限 6821、上限 35283

        // 低于下限 → 按下限；养老 8% + 医疗 2%+3 + 失业 0.5%，公积金 12%
        TaxService.InsuranceResult low = taxService.insurance(empId, "2026-01",
                LocalDate.of(2026, 1, 31), new BigDecimal("5000"));
        assertThat(low.social()).isEqualByComparingTo("719.21");
        assertThat(low.fund()).isEqualByComparingTo("818.52");

        // 高于上限 → 按上限
        TaxService.InsuranceResult high = taxService.insurance(empId, "2026-01",
                LocalDate.of(2026, 1, 31), new BigDecimal("50000"));
        assertThat(high.social()).isEqualByComparingTo("3707.72");
        assertThat(high.fund()).isEqualByComparingTo("4233.96");

        // 手工指定基数不再被当月应发影响
        jdbc.update("""
                UPDATE emp_social_insurance SET social_base = 10000
                 WHERE employee_id = ?
                """, empId);
        TaxService.InsuranceResult fixed = taxService.insurance(empId, "2026-01",
                LocalDate.of(2026, 1, 31), new BigDecimal("5000"));
        assertThat(fixed.social()).isEqualByComparingTo("1053.00");
        assertThat(fixed.fund()).isEqualByComparingTo("1200.00");
    }

    @Test
    void 专项附加扣除按生效区间求和() {
        Long empId = employee("E001", LocalDate.of(2020, 3, 1));
        jdbc.update("""
                INSERT INTO emp_special_deduction
                  (employee_id, deduction_type, monthly_amount, effective_from, effective_to)
                VALUES (?, 'CHILD_EDU', 2000, '2025-01-01', NULL),
                       (?, 'HOUSING_LOAN', 1000, '2025-01-01', NULL),
                       (?, 'ELDERLY_CARE', 3000, '2024-01-01', '2024-12-31')
                """, empId, empId, empId);

        assertThat(taxService.specialDeduction(empId, LocalDate.of(2025, 6, 30)))
                .isEqualByComparingTo("3000.00");
        // 已过期的赡养老人不再计入
        assertThat(taxService.specialDeduction(empId, LocalDate.of(2024, 6, 30)))
                .isEqualByComparingTo("3000.00");
        assertThat(taxService.specialDeduction(empId, LocalDate.of(2025, 1, 1)))
                .isEqualByComparingTo("3000.00");
    }

    @Test
    void 锁定累加进台账_解锁减回() {
        Long empId = employee("E001", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-01"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        periodService.lock(periodId, 2L);

        // 应发 8500（8000 + 5 年工龄 500）；基数无历史 → 用当月应发 8500（在 6821–35283 内）
        assertThat(jdbc.queryForObject(
                "SELECT cum_income FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("8500.00");
        assertThat(jdbc.queryForObject(
                "SELECT cum_social FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("895.50");
        assertThat(jdbc.queryForObject(
                "SELECT cum_fund FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("1020.00");
        // 8500 − 5000 − 895.50 − 1020.00 = 1584.50 → 3% = 47.54
        assertThat(jdbc.queryForObject(
                "SELECT cum_tax FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("47.54");

        periodService.unlock(periodId, 2L, "复核重算");

        assertThat(jdbc.queryForObject("""
                SELECT cum_income + cum_social + cum_fund + cum_special_deduction + cum_tax
                  FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026
                """, BigDecimal.class, empId)).isEqualByComparingTo("0.00");
    }

    @Test
    void 跨年累计清零() {
        Long empId = employee("E001", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));

        long december = periodService.create(
                new PeriodCreateReq(null, "2025-12"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(december, 1L);
        periodService.lock(december, 2L);

        long january = periodService.create(
                new PeriodCreateReq(null, "2026-01"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(january, 1L);
        periodService.lock(january, 2L);

        // 两个年度各自一行
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_ytd WHERE employee_id = ?", Integer.class, empId))
                .isEqualTo(2);
        // 2025-12 是 2025 年首次运行 → 按 1 个月减除：
        // 基数取当月 8500：社保 895.50、公积金 1020.00 → 1584.50×3% = 47.54
        assertThat(jdbc.queryForObject(
                "SELECT cum_tax FROM pay_ytd WHERE employee_id = ? AND tax_year = 2025",
                BigDecimal.class, empId)).isEqualByComparingTo("47.54");
        // 2026-01 从 months=1 重新累计；基数取上月（已锁定）平均 8500：
        // 社保 895.50、公积金 1020.00 → 应纳税所得额 1584.50 → 税 47.54
        assertThat(jdbc.queryForObject(
                "SELECT cum_tax FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("47.54");
        assertThat(jdbc.queryForObject(
                "SELECT cum_income FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("8500.00");
    }

    @Test
    void 首次运行按一个月减除() {
        // 无社保；2026-09 首次生成 → 减除 5000（不是 9×5000）
        Long empId = employeeNoInsurance("E200", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-09"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);

        // 应发 8600（8000 + 6 年工龄 600）：(8600−5000)×3% = 108
        assertThat(itemAmount(periodId, "E200", "TAX")).isEqualByComparingTo("108.00");
    }

    @Test
    void 连续三个月累计不是固定值() {
        Long empId = employeeNoInsurance("E300", LocalDate.of(2021, 1, 1));
        profile(empId, "29500", LocalDate.of(2021, 1, 1));

        long jan = generateAndLock("2026-01");
        long feb = generateAndLock("2026-02");
        long mar = generateAndLock("2026-03");

        // 每月应发 30000：750 / 1730 / 2500（累计预扣进 10% 档）
        assertThat(itemAmount(jan, "E300", "TAX")).isEqualByComparingTo("750.00");
        assertThat(itemAmount(feb, "E300", "TAX")).isEqualByComparingTo("1730.00");
        assertThat(itemAmount(mar, "E300", "TAX")).isEqualByComparingTo("2500.00");
        assertThat(jdbc.queryForObject(
                "SELECT cum_tax FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                BigDecimal.class, empId)).isEqualByComparingTo("4980.00");
        assertThat(jdbc.queryForObject(
                "SELECT paid_months FROM pay_ytd WHERE employee_id = ? AND tax_year = 2026",
                Integer.class, empId)).isEqualTo(3);
    }

    @Test
    void 期初数据参与累计() {
        Long empId = employeeNoInsurance("E400", LocalDate.of(2020, 3, 1));
        profile(empId, "8000", LocalDate.of(2020, 3, 1));
        // 年中迁移：1–8 月已在旧系统发薪
        jdbc.update("""
                INSERT INTO pay_ytd
                  (employee_id, tax_year, company_id, cum_income, cum_social, cum_fund,
                   cum_special_deduction, cum_tax, paid_months, last_period_ym)
                VALUES (?, 2026, 1, 80000, 0, 0, 0, 1000, 8, '2026-08')
                """, empId);

        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-09"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);

        // cumIncome 80000+8600=88600，months=9 → 88600−45000=43600
        // → 43600×10%−2520 = 1840；已预扣 1000 → 840
        assertThat(itemAmount(periodId, "E400", "TAX")).isEqualByComparingTo("840.00");
    }

    @Test
    void 中途入职按已发月份减除() {
        Long empId = employeeNoInsurance("E500", LocalDate.of(2026, 5, 1));
        profile(empId, "8000", LocalDate.of(2026, 5, 1));
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-09"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);

        // 系统 9 月才首次发薪 → 只减 1 个月：8000−5000=3000 → 90
        assertThat(itemAmount(periodId, "E500", "TAX")).isEqualByComparingTo("90.00");
    }

    private long generateAndLock(String periodYm) {
        long periodId = periodService.create(
                new PeriodCreateReq(null, periodYm), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        periodService.lock(periodId, 2L);
        return periodId;
    }

    private BigDecimal itemAmount(long periodId, String empNo, String itemCode) {
        return jdbc.queryForObject("""
                SELECT i.amount FROM pay_record_item i
                  JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND r.emp_no = ? AND i.item_code = ?
                """, BigDecimal.class, periodId, empNo, itemCode);
    }

    /** 不建参保信息（社保/公积金为 0，便于验证纯个税逻辑）。 */
    private Long employeeNoInsurance(String empNo, LocalDate hireDate) {
        return employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD",
                "11010119900101" + Math.abs(empNo.hashCode() % 10000), null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT, hireDate, null, null, 1, null),
                TestDb.COMPANY_DEFAULT);
    }

    // ------------------------------------------------------------------ 工具

    private Long employee(String empNo, LocalDate hireDate) {
        Long id = employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD", "110101199001011234", null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT, hireDate, null, null, 1, null),
                TestDb.COMPANY_DEFAULT);
        // 默认参加北京社保（基数自动计算）
        jdbc.update("""
                INSERT INTO emp_social_insurance (employee_id, city_code, effective_from)
                VALUES (?, 'BJ', '2025-01-01')
                """, id);
        return id;
    }

    private void profile(Long employeeId, String salary, LocalDate from) {
        compProfileService.save(new CompProfileSaveReq(null, employeeId, from,
                new BigDecimal(salary), "初始", java.util.List.of()));
    }
}
