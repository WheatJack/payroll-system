package com.insbean.payroll.run;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.run.dto.RunErrorDTO;
import com.insbean.payroll.run.dto.RunProgressDTO;
import com.insbean.payroll.run.mapper.PayRecordMapper;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateServiceTest extends AbstractDbTest {

    private static final long OPERATOR = 1L;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private PayRecordMapper recordMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Long periodId;
    private int employeeCount;

    @BeforeEach
    void setUpPeriod() {
        periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();

        Long e1 = createEmployee("E001", "110101199001011234", TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1));
        Long e2 = createEmployee("E002", "110101199001015678", TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT,
                LocalDate.of(2022, 6, 15));
        createEmployee("E999", "110101199001019999", TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1));
        employeeCount = 3;

        compProfileService.save(new CompProfileSaveReq(null, e1, LocalDate.of(2020, 3, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));
        compProfileService.save(new CompProfileSaveReq(null, e2, LocalDate.of(2022, 6, 15),
                new BigDecimal("6000"), "入职定薪", List.of()));
        // E999 故意不配薪酬档案
    }

    @Test
    void 重复生成不产生第二条记录() {
        Long run1 = generateService.startGenerate(periodId, OPERATOR);
        Long run2 = generateService.startGenerate(periodId, OPERATOR);

        assertThat(run1).isNotEqualTo(run2);
        assertThat(recordMapper.countActive(periodId, TestDb.COMPANY_DEFAULT))
                .isEqualTo(employeeCount - 1);
        assertThat(generateService.progress(run2).status()).isEqualTo("SUCCESS");
    }

    @Test
    void 重复生成会保留人工录入的变动项() {
        generateService.startGenerate(periodId, OPERATOR);

        // 模拟编制人在界面上录入了绩效奖金（source=MANUAL）
        jdbc.update("""
                INSERT INTO pay_record_item (record_id, item_code, item_name, category, direction, amount, source, sort_no)
                SELECT r.id, 'PERF_BONUS', '绩效奖金', 'VARIABLE', 1, 2000.00, 'MANUAL', 40
                  FROM pay_record r
                 WHERE r.emp_no = 'E001' AND r.period_id = ?
                """, periodId);

        generateService.startGenerate(periodId, OPERATOR);

        BigDecimal amount = jdbc.queryForObject("""
                SELECT i.amount FROM pay_record_item i JOIN pay_record r ON r.id = i.record_id
                 WHERE r.emp_no = 'E001' AND r.period_id = ? AND i.item_code = 'PERF_BONUS'
                """, BigDecimal.class, periodId);
        assertThat(amount).isEqualByComparingTo("2000.00");
    }

    @Test
    void 跨主体同月重复时报错能指出是哪一家() {
        Long beijingPeriod = periodService.create(
                new PeriodCreateReq(TestDb.COMPANY_BEIJING, "2026-11"), TestDb.COMPANY_BEIJING).id();
        Long shanghaiPeriod = periodService.create(
                new PeriodCreateReq(TestDb.COMPANY_SHANGHAI, "2026-11"), TestDb.COMPANY_SHANGHAI).id();

        // 张三同时在北京和上海有任职区间（数据错误，但正是三层防线的目标场景）
        Long zhang = createEmployee("E100", "110101199001017777",
                TestDb.COMPANY_BEIJING, TestDb.DEPT_BEIJING, LocalDate.of(2020, 1, 1));
        jdbc.update("""
                INSERT INTO emp_employment (employee_id, company_id, dept_id, start_date, change_type)
                VALUES (?, ?, ?, ?, 'TRANSFER')
                """, zhang, TestDb.COMPANY_SHANGHAI, TestDb.DEPT_SHANGHAI, LocalDate.of(2026, 11, 1));
        compProfileService.save(new CompProfileSaveReq(null, zhang, LocalDate.of(2020, 1, 1),
                new BigDecimal("9000"), "入职定薪", List.of()));

        generateService.startGenerate(beijingPeriod, OPERATOR);

        assertThatThrownBy(() -> generateService.startGenerate(shanghaiPeriod, OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("北京");
    }

    @Test
    void 无薪酬档案的员工被跳过并记录原因() {
        Long runId = generateService.startGenerate(periodId, OPERATOR);
        RunProgressDTO p = generateService.progress(runId);

        assertThat(p.skipped()).containsEntry("E999", "未配置薪酬档案");
        assertThat(p.total()).isEqualTo(employeeCount);
        assertThat(p.success()).isEqualTo(employeeCount - 1);
        assertThat(recordMapper.countActive(periodId, TestDb.COMPANY_DEFAULT))
                .isEqualTo(employeeCount - 1);
    }

    @Test
    void 生成结果的基本工资与工龄工资符合规则() {
        generateService.startGenerate(periodId, OPERATOR);

        // 2020-03-01 到 2026-11-30 = 6 整年 → 600
        assertThat(itemAmount("E001", "BASE_SALARY")).isEqualByComparingTo("8000.00");
        assertThat(itemAmount("E001", "SENIORITY_PAY")).isEqualByComparingTo("600.00");
        // 2022-06-15 到 2026-11-30 = 4 整年 → 400
        assertThat(itemAmount("E002", "SENIORITY_PAY")).isEqualByComparingTo("400.00");
    }

    @Test
    void 合计等于明细加总且期间汇总被回写() {
        generateService.startGenerate(periodId, OPERATOR);

        BigDecimal gross = jdbc.queryForObject("""
                SELECT SUM(i.amount) FROM pay_record_item i
                  JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND i.direction = 1
                """, BigDecimal.class, periodId);

        var period = periodService.get(periodId);
        assertThat(period.status()).isEqualTo("EDITING");
        assertThat(period.employeeCount()).isEqualTo(employeeCount - 1);
        // 8600 + 6400 = 15000
        assertThat(period.totalGross()).isEqualByComparingTo(gross);
        assertThat(period.totalGross()).isEqualByComparingTo("15000.00");
        assertThat(period.totalNet()).isEqualByComparingTo("15000.00");
    }

    @Test
    void 锁定期间拒绝生成() {
        generateService.startGenerate(periodId, OPERATOR);
        periodService.lock(periodId, 2L);

        assertThatThrownBy(() -> generateService.startGenerate(periodId, OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已锁定");
    }

    @Test
    void 入职当月按工作日折算基本工资() {
        // 2026-11-16(周一) 入职，当月剩余 11 个工作日（15 个自然日）
        Long empId = createEmployee("E200", "110101199001016666",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT, LocalDate.of(2026, 11, 16));
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2026, 11, 16),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(periodId, OPERATOR);

        // 8000 / 21.75 * 11 = 4045.977… → 4045.98
        // 若误用自然日会得到 8000 / 21.75 * 15 = 5517.24
        assertThat(itemAmount("E200", "BASE_SALARY")).isEqualByComparingTo("4045.98");
    }

    @Test
    void 生成失败的原因写入批次记录() {
        Long beijingPeriod = periodService.create(
                new PeriodCreateReq(TestDb.COMPANY_BEIJING, "2026-11"), TestDb.COMPANY_BEIJING).id();
        Long shanghaiPeriod = periodService.create(
                new PeriodCreateReq(TestDb.COMPANY_SHANGHAI, "2026-11"), TestDb.COMPANY_SHANGHAI).id();
        Long zhang = createEmployee("E100", "110101199001017777",
                TestDb.COMPANY_BEIJING, TestDb.DEPT_BEIJING, LocalDate.of(2020, 1, 1));
        jdbc.update("""
                INSERT INTO emp_employment (employee_id, company_id, dept_id, start_date, change_type)
                VALUES (?, ?, ?, ?, 'TRANSFER')
                """, zhang, TestDb.COMPANY_SHANGHAI, TestDb.DEPT_SHANGHAI, LocalDate.of(2026, 11, 1));
        compProfileService.save(new CompProfileSaveReq(null, zhang, LocalDate.of(2020, 1, 1),
                new BigDecimal("9000"), "入职定薪", List.of()));

        generateService.startGenerate(beijingPeriod, OPERATOR);
        assertThatThrownBy(() -> generateService.startGenerate(shanghaiPeriod, OPERATOR))
                .isInstanceOf(BizException.class);

        // R10：失败原因必须落在 pay_run.error_msg，不能只靠 HTTP 异常
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT id, status, error_msg FROM pay_run WHERE period_id = ? ORDER BY id DESC LIMIT 1",
                shanghaiPeriod);
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat((String) run.get("error_msg")).contains("北京");

        RunProgressDTO progress = generateService.progress(((Number) run.get("id")).longValue());
        assertThat(progress.status()).isEqualTo("FAILED");
        assertThat(progress.errors()).extracting(RunErrorDTO::message)
                .anyMatch(m -> m != null && m.contains("北京"));
    }

    @Test
    void 无效批次号返回明确错误() {
        assertThatThrownBy(() -> generateService.progress(999999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("批次");
    }

    private BigDecimal itemAmount(String empNo, String itemCode) {
        return jdbc.queryForObject("""
                SELECT i.amount FROM pay_record_item i JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND r.emp_no = ? AND i.item_code = ?
                """, BigDecimal.class, periodId, empNo, itemCode);
    }

    private Long createEmployee(String empNo, String idNo, long companyId, long deptId, LocalDate hireDate) {
        return employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD", idNo, null,
                "REGULAR", "P6", "工程师", deptId,
                hireDate, null, null, 1, null), companyId);
    }
}
