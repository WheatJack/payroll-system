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
import com.insbean.payroll.record.RecordService;
import com.insbean.payroll.record.dto.ChangesetChange;
import com.insbean.payroll.record.dto.ChangesetItem;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.setting.ConfigService;
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
    private RecordService recordService;

    @Autowired
    private ConfigService configService;

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
    void 入职日等于期间首日不发折算工资() {
        Long p = periodService.create(new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT).id();
        Long empId = createEmployee("E300", "110101199001018888",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT, LocalDate.of(2026, 12, 1));
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2026, 12, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(p, OPERATOR);

        // 2026-12 有 23 个工作日。误判成不足月会得到 8000/21.75×23 = 8459.77，全月反而多发
        assertThat(itemAmountIn(p, "E300", "BASE_SALARY")).isEqualByComparingTo("8000.00");
    }

    @Test
    void 离职日等于期间末日不发折算工资() {
        Long p = periodService.create(new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT).id();
        Long empId = createEmployee("E301", "110101199001017777",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), 2);
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 1, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(p, OPERATOR);

        assertThat(itemAmountIn(p, "E301", "BASE_SALARY")).isEqualByComparingTo("8000.00");
    }

    @Test
    void 工作日少的月份全月在岗也是全额() {
        Long p = periodService.create(new PeriodCreateReq(null, "2026-02"), TestDb.COMPANY_DEFAULT).id();
        Long empId = createEmployee("E302", "110101199001016666",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT, LocalDate.of(2026, 2, 1));
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2026, 2, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(p, OPERATOR);

        // 2026-02 只有 20 个工作日，但全月在岗就是全额，不因工作日少而少发
        assertThat(itemAmountIn(p, "E302", "BASE_SALARY")).isEqualByComparingTo("8000.00");
    }

    @Test
    void 离职状态的人当月仍要发工资() {
        Long empId = createEmployee("E303", "110101199001015555",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 11, 15), 2);
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 1, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(periodId, OPERATOR);

        // 2026-11-01..11-15 共 10 个工作日 → 8000 / 21.75 × 10 = 3678.16
        // 用 status=1 过滤的话这条记录根本不会生成，HR 先置离职状态就一分钱不发
        assertThat(itemAmount("E303", "BASE_SALARY")).isEqualByComparingTo("3678.16");
    }

    @Test
    void 计薪天数参数取自期间而不是硬编码() {
        jdbc.update("UPDATE pay_period SET hpd = 20.00 WHERE id = ?", periodId);
        Long empId = createEmployee("E400", "110101199001014444",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT, LocalDate.of(2026, 11, 16));
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2026, 11, 16),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(periodId, OPERATOR);

        // 8000 / 20 × 11 = 4400.00；写死 21.75 会得到 4045.98
        assertThat(itemAmount("E400", "BASE_SALARY")).isEqualByComparingTo("4400.00");
    }

    @Test
    void 部门快照取期间末日的归属部门() {
        jdbc.update("""
                INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status)
                VALUES (10, 1, 1, 'D010', '研发组', ',1,', 10, 1)
                """);
        Long empId = createEmployee("E500", "110101199001013333",
                TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT, LocalDate.of(2020, 1, 1));
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 1, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(periodId, OPERATOR);
        assertThat(deptNameOf(periodId, "E500")).isEqualTo("综合部");

        // 12 月调到研发组
        employeeService.transfer(empId, TestDb.COMPANY_DEFAULT, 10L, LocalDate.of(2026, 12, 1));

        // 重算 11 月仍应取 11-30 的归属部门，而不是今天的部门
        generateService.startGenerate(periodId, OPERATOR);
        assertThat(deptNameOf(periodId, "E500")).isEqualTo("综合部");

        Long december = periodService.create(new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(december, OPERATOR);
        assertThat(deptNameOf(december, "E500")).isEqualTo("研发组");
    }

    @Test
    void 生成时写入工资表列快照供导出使用() {
        generateService.startGenerate(periodId, OPERATOR);

        String snapshot = jdbc.queryForObject(
                "SELECT column_snapshot FROM pay_period WHERE id = ?", String.class, periodId);

        // 导出列必须取生成时的快照，而不是当时的启用科目
        assertThat(snapshot).contains("BASE_SALARY").contains("SENIORITY_PAY").contains("itemCode");
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

    @Test
    void 重新生成不改变手工改过的金额() {
        // 4 位精度变动项 + 可手工编辑的固定项，两者都必须原样保留
        jdbc.update("""
                INSERT INTO pay_item (company_id, item_code, item_name, category, direction,
                                      data_type, precision_scale, include_in_gross, editable,
                                      importable, sort_no, status)
                VALUES (0, 'PRECISE', '高精度津贴', 'VARIABLE', 1, 'AMOUNT', 4, 1, 1, 1, 60, 1),
                       (0, 'FIXED_BONUS', '固定奖金', 'FIXED', 1, 'AMOUNT', 2, 1, 1, 1, 45, 1)
                """);
        generateService.startGenerate(periodId, OPERATOR);

        Long recordId = jdbc.queryForObject(
                "SELECT id FROM pay_record WHERE period_id = ? AND emp_no = 'E001'",
                Long.class, periodId);
        int version = jdbc.queryForObject(
                "SELECT version_no FROM pay_record WHERE id = ?", Integer.class, recordId);

        // 编制台手工改值：4 位金额 1234.5678 + 手工新增的固定项 500
        recordService.applyChangeset(periodId, new ChangesetReq(List.of(
                new ChangesetChange(recordId, version, List.of(
                        new ChangesetItem("PRECISE", "1234.5678"),
                        new ChangesetItem("FIXED_BONUS", "500.00"))))));

        // 重新生成：系统能算的项重算，手工项必须原样保留
        generateService.startGenerate(periodId, OPERATOR);

        assertThat(itemAmount("E001", "PRECISE")).isEqualByComparingTo("1234.5678");
        assertThat(itemAmount("E001", "FIXED_BONUS")).isEqualByComparingTo("500.00");
        assertThat(itemSource(recordId, "PRECISE")).isEqualTo("MANUAL");
        assertThat(itemSource(recordId, "FIXED_BONUS")).isEqualTo("MANUAL");

        BigDecimal gross = jdbc.queryForObject(
                "SELECT gross_amount FROM pay_record WHERE id = ?", BigDecimal.class, recordId);
        // 8000 + 600 工龄 + 1234.5678 + 500，合计不被二次舍入
        assertThat(gross).isEqualByComparingTo("10334.5678");
    }

    @Test
    void 改工龄单价后同一员工工龄工资翻倍() {
        configService.update(ConfigService.SENIORITY_PAY_PER_YEAR, "200");
        generateService.startGenerate(periodId, OPERATOR);

        // E001 入职 2020-03-01，2026-11 期间末 = 6 整年 → 6 × 200 = 1200
        assertThat(itemAmount("E001", "SENIORITY_PAY")).isEqualByComparingTo("1200.00");
    }

    @Test
    void 改月计薪天数后不足月折算分母随之变化() {
        configService.update(ConfigService.MONTHLY_PAYABLE_DAYS, "30");
        // 系统参数是"新建期间"的默认值；已有期间保留自己的 hpd（期间级参数优先）
        long december = periodService.create(
                new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT).id();
        assertThat(periodService.get(december).hpd()).isEqualByComparingTo("30");

        Long e100 = createEmployee("E100", "110101199001017777", TestDb.COMPANY_DEFAULT,
                TestDb.DEPT_DEFAULT, LocalDate.of(2026, 12, 16));
        compProfileService.save(new CompProfileSaveReq(null, e100, LocalDate.of(2026, 12, 16),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(december, OPERATOR);

        // 12-16..12-31 共 12 个工作日；8000 × 12 / 30 = 3200.00（分母不再是 21.75）
        assertThat(itemAmountIn(december, "E100", "BASE_SALARY")).isEqualByComparingTo("3200.00");
    }

    @Test
    void 配置节假日后该日不再计入工作日() {
        jdbc.update("UPDATE sys_config SET config_value = '2026-11-02' WHERE config_key = 'payroll.calendar.holidays'");
        Long e101 = createEmployee("E101", "110101199001018888", TestDb.COMPANY_DEFAULT,
                TestDb.DEPT_DEFAULT, LocalDate.of(2026, 11, 2));
        compProfileService.save(new CompProfileSaveReq(null, e101, LocalDate.of(2026, 11, 2),
                new BigDecimal("8000"), "入职定薪", List.of()));

        generateService.startGenerate(periodId, OPERATOR);

        // 11-02..11-30 原本 21 个工作日，剔除 11-02 后 20 个：8000 × 20 / 21.75 = 7356.32
        assertThat(itemAmount("E101", "BASE_SALARY")).isEqualByComparingTo("7356.32");
    }

    private String itemSource(Long recordId, String itemCode) {
        return jdbc.queryForObject("""
                SELECT source FROM pay_record_item WHERE record_id = ? AND item_code = ?
                """, String.class, recordId, itemCode);
    }

    private BigDecimal itemAmount(String empNo, String itemCode) {
        return jdbc.queryForObject("""
                SELECT i.amount FROM pay_record_item i JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND r.emp_no = ? AND i.item_code = ?
                """, BigDecimal.class, periodId, empNo, itemCode);
    }

    private Long createEmployee(String empNo, String idNo, long companyId, long deptId, LocalDate hireDate) {
        return createEmployee(empNo, idNo, companyId, deptId, hireDate, null, 1);
    }

    private Long createEmployee(String empNo, String idNo, long companyId, long deptId,
                                LocalDate hireDate, LocalDate leaveDate, int status) {
        return employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD", idNo, null,
                "REGULAR", "P6", "工程师", deptId,
                hireDate, null, leaveDate, status, null), companyId);
    }

    private BigDecimal itemAmountIn(Long periodId, String empNo, String itemCode) {
        return jdbc.queryForObject("""
                SELECT i.amount FROM pay_record_item i JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND r.emp_no = ? AND i.item_code = ?
                """, BigDecimal.class, periodId, empNo, itemCode);
    }

    private String deptNameOf(Long periodId, String empNo) {
        return jdbc.queryForObject(
                "SELECT dept_name FROM pay_record WHERE period_id = ? AND emp_no = ?",
                String.class, periodId, empNo);
    }
}
