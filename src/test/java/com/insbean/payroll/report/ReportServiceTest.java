package com.insbean.payroll.report;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.report.dto.AnnualReportRow;
import com.insbean.payroll.report.dto.MonthlyReportRow;
import com.insbean.payroll.report.dto.ReportQuery;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 月度/年度报表（Task 19）验收。
 *
 * <p>覆盖评审强调的口径：只统计已锁定期间、五种数据范围各查一次、合计与
 * pay_record 逐分相等、employeeCount 去重、年度按归属期间归集。
 */
class ReportServiceTest extends AbstractDbTest {

    private static final long COMPILER = 1L;
    private static final long LOCKER = 2L;

    @Autowired
    private ReportService reportService;

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

    private Long emp2;

    @BeforeEach
    void setUpPayroll() {
        // 部门 10 是部门 1 的下级，部门 20 与部门 1 平级（数据范围 DEPT_SUB / DEPT 的判定依据）
        jdbc.update("""
                INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status)
                VALUES (10, 1, 1, 'D010', '研发组', ',1,', 10, 1),
                       (20, 1, 0, 'D020', '财务部', '', 20, 1)
                """);

        Long emp1 = employee("E001", "张三", 1, 1, 1);
        emp2 = employee("E002", "李四", 2, 10, 1);
        Long emp3 = employee("E003", "王五", 3, 20, 1);
        Long emp4 = employee("E004", "赵六", 4, 2, 2);
        Long emp5 = employee("E005", "钱七", 5, 3, 3);
        for (Long id : List.of(emp1, emp2, emp3, emp4, emp5)) {
            compProfileService.save(new CompProfileSaveReq(null, id, LocalDate.of(2020, 3, 1),
                    new BigDecimal("8000"), "初始", List.of()));
        }

        // 默认主体：10 月编制中（未锁定，不得进报表）、11/12 月已锁定
        long oct = period(1, "2026-10", false);
        long nov = period(1, "2026-11", true);
        long dec = period(1, "2026-12", true);
        period(2, "2026-11", true);
        period(3, "2026-11", true);
        assertThat(oct).isPositive();
        assertThat(nov).isPositive();
        assertThat(dec).isPositive();
    }

    // ------------------------------------------------------------------ 月度

    @Test
    void 只统计已锁定期间() {
        // 2026-10 生成过但没锁定
        assertThat(reportService.monthly(ReportQuery.monthly("2026-10", null, null))).isEmpty();
        assertThat(reportService.monthly(ReportQuery.monthly("2026-11", null, null))).isNotEmpty();
    }

    @Test
    void 五种数据范围各查一次报表() {
        long totalRows = reportService.monthly(ReportQuery.monthly("2026-11", null, null)).size();
        assertThat(totalRows).isEqualTo(5); // 3 个默认主体部门 + 北京 + 上海

        login(1, 1, "COMPANY");
        List<MonthlyReportRow> company = reportService.monthly(ReportQuery.monthly("2026-11", null, null));
        assertThat(company).hasSize(3);
        assertThat(company).allSatisfy(r -> assertThat(r.companyId()).isEqualTo(1L));

        login(1, 1, "DEPT_SUB");
        List<MonthlyReportRow> subtree = reportService.monthly(ReportQuery.monthly("2026-11", null, null));
        assertThat(subtree).extracting(MonthlyReportRow::deptId).containsExactlyInAnyOrder(1L, 10L);

        login(1, 1, "DEPT");
        List<MonthlyReportRow> dept = reportService.monthly(ReportQuery.monthly("2026-11", null, null));
        assertThat(dept).extracting(MonthlyReportRow::deptId).containsExactly(1L);

        CurrentUser.set(new CurrentUser(1L, 1L, perms(), "SELF", emp2));
        List<MonthlyReportRow> self = reportService.monthly(ReportQuery.monthly("2026-11", null, null));
        assertThat(self).hasSize(1);
        assertThat(self.get(0).deptId()).isEqualTo(10L);
        assertThat(self.get(0).employeeCount()).isEqualTo(1);
    }

    @Test
    void 跨主体查询返回403() {
        login(1, 1, "COMPANY");
        assertThatThrownBy(() -> reportService.monthly(ReportQuery.monthly("2026-11", 2L, null)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> reportService.annual(ReportQuery.annual("2026", "EMP", 2L, null)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void 报表合计与payRecord逐分相等() {
        List<MonthlyReportRow> rows = reportService.monthly(ReportQuery.monthly("2026-11", null, null));
        BigDecimal reportGross = rows.stream().map(MonthlyReportRow::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reportNet = rows.stream().map(MonthlyReportRow::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal dbGross = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.gross_amount), 0)
                  FROM pay_record r
                  JOIN pay_period p ON p.id = r.period_id AND p.status IN ('LOCKED', 'ARCHIVED')
                 WHERE r.deleted = 0 AND r.period_ym = '2026-11'
                """, BigDecimal.class);
        BigDecimal dbNet = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.net_amount), 0)
                  FROM pay_record r
                  JOIN pay_period p ON p.id = r.period_id AND p.status IN ('LOCKED', 'ARCHIVED')
                 WHERE r.deleted = 0 AND r.period_ym = '2026-11'
                """, BigDecimal.class);

        assertThat(reportGross).isEqualByComparingTo(dbGross);
        assertThat(reportNet).isEqualByComparingTo(dbNet);
        assertThat(reportGross).isGreaterThan(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ 年度

    @Test
    void 年度按人汇总_employeeCount去重不等于各月相加() {
        List<AnnualReportRow> rows = reportService.annual(ReportQuery.annual("2026", "EMP", null, null));
        assertThat(rows).hasSize(5);

        AnnualReportRow emp1 = rows.stream()
                .filter(r -> "E001".equals(r.empNo())).findFirst().orElseThrow();
        // 11、12 两期都有记录，人数仍然是 1
        assertThat(emp1.employeeCount()).isEqualTo(1);
        assertThat(emp1.groupBy()).isEqualTo("EMP");
        assertThat(emp1.year()).isEqualTo("2026");

        BigDecimal dbGross = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.gross_amount), 0)
                  FROM pay_record r
                  JOIN pay_period p ON p.id = r.period_id AND p.status IN ('LOCKED', 'ARCHIVED')
                 WHERE r.deleted = 0 AND r.emp_no = 'E001'
                   AND r.period_ym BETWEEN '2026-01' AND '2026-12'
                """, BigDecimal.class);
        assertThat(emp1.grossAmount()).isEqualByComparingTo(dbGross);
        assertThat(emp1.grossAmount()).isGreaterThan(new BigDecimal("8000.00")); // 两期之和
    }

    @Test
    void 年度按部门汇总_多主体时必须指定主体() {
        assertThatThrownBy(() -> reportService.annual(ReportQuery.annual("2026", "DEPT", null, null)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);

        List<AnnualReportRow> rows = reportService.annual(ReportQuery.annual("2026", "DEPT", 1L, null));
        assertThat(rows).extracting(AnnualReportRow::deptId).containsExactlyInAnyOrder(1L, 10L, 20L);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.groupBy()).isEqualTo("DEPT");
            assertThat(r.employeeCount()).isEqualTo(1);
        });

        BigDecimal reportGross = rows.stream().map(AnnualReportRow::grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dbGross = jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.gross_amount), 0)
                  FROM pay_record r
                  JOIN pay_period p ON p.id = r.period_id AND p.status IN ('LOCKED', 'ARCHIVED')
                 WHERE r.deleted = 0 AND r.company_id = 1
                   AND r.period_ym BETWEEN '2026-01' AND '2026-12'
                """, BigDecimal.class);
        assertThat(reportGross).isEqualByComparingTo(dbGross);
    }

    @Test
    void 年度SELF只返回本人且兼容EMPLOYEE写法() {
        CurrentUser.set(new CurrentUser(1L, 1L, perms(), "SELF", emp2));
        List<AnnualReportRow> rows = reportService.annual(
                ReportQuery.annual("2026", "EMPLOYEE", null, null));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).employeeId()).isEqualTo(emp2);
        assertThat(rows.get(0).groupBy()).isEqualTo("EMP");
        assertThat(rows.get(0).employeeCount()).isEqualTo(1);
    }

    @Test
    void 参数校验() {
        assertThatThrownBy(() -> reportService.monthly(ReportQuery.monthly("2026-13", null, null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> reportService.annual(ReportQuery.annual("26", "EMP", null, null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> reportService.annual(ReportQuery.annual("2026", "TEAM", null, null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
    }

    // ------------------------------------------------------------------ 工具

    private Long employee(String empNo, String name, int seq, long deptId, long companyId) {
        return employeeService.create(new EmployeeSaveReq(
                companyId, empNo, name, 1, "ID_CARD", "11010119900101" + String.format("%04d", seq), null,
                "REGULAR", "P6", "工程师", deptId,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);
    }

    private long period(long companyId, String ym, boolean lock) {
        long periodId = periodService.create(new PeriodCreateReq(companyId, ym), companyId).id();
        generateService.startGenerate(periodId, COMPILER);
        if (lock) {
            periodService.lock(periodId, LOCKER);
        }
        return periodId;
    }

    private void login(long userId, long companyId, String scope) {
        CurrentUser.set(new CurrentUser(userId, companyId, perms(), scope, null));
    }

    private static Set<String> perms() {
        return Set.of("payroll:record:view", "payroll:record:edit",
                "payroll:period:lock", "payroll:period:unlock");
    }
}
