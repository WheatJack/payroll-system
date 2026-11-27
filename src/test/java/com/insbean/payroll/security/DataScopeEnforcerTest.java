package com.insbean.payroll.security;

import com.insbean.payroll.common.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.period.dto.PeriodDTO;
import com.insbean.payroll.record.RecordService;
import com.insbean.payroll.record.dto.ChangesetChange;
import com.insbean.payroll.record.dto.ChangesetItem;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.record.dto.GridRow;
import com.insbean.payroll.run.GenerateService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围（data_scope）落地验证。
 *
 * <p>最严重的越权面：EMPLOYEE 角色本来就有 payroll:record:view（data_scope=SELF），
 * 如果只做功能权限不做数据范围，一个普通员工就能拉走全公司工资表。
 */
class DataScopeEnforcerTest extends AbstractDbTest {

    private static final long USER = 1L;

    @Autowired
    private DataScopeEnforcer enforcer;

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

    private Long selfEmployee;      // 部门 1
    private Long childDeptEmployee; // 部门 10（部门 1 的下级）
    private Long otherDeptEmployee; // 部门 20（与部门 1 平级）
    private Long otherCompanyEmployee;
    private Long periodId;

    @BeforeEach
    void setUpOrg() {
        jdbc.update("""
                INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status)
                VALUES (10, 1, 1, 'D010', '研发组', ',1,', 10, 1),
                       (20, 1, 0, 'D020', '财务部', '',     20, 1)
                """);

        selfEmployee = employee("E001", TestDb.COMPANY_DEFAULT, TestDb.DEPT_DEFAULT);
        childDeptEmployee = employee("E002", TestDb.COMPANY_DEFAULT, 10L);
        otherDeptEmployee = employee("E003", TestDb.COMPANY_DEFAULT, 20L);
        otherCompanyEmployee = employee("E004", TestDb.COMPANY_BEIJING, TestDb.DEPT_BEIJING);

        periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, USER);
        // 部门主管/本人只能看已锁定期间（A2）：数据范围用例统一在锁定后验证
        periodService.lock(periodId, 2L);
    }

    @Test
    void SELF用户只能看到自己那一行工资() {
        asUser("SELF", selfEmployee, TestDb.DEPT_DEFAULT);

        assertThat(recordService.grid(periodId).rows())
                .extracting(GridRow::empNo)
                .containsExactly("E001");
    }

    @Test
    void 期间合计按数据范围收窄() {
        BigDecimal ownGross = jdbc.queryForObject("""
                SELECT gross_amount FROM pay_record
                 WHERE period_id = ? AND emp_no = 'E001' AND deleted = 0
                """, BigDecimal.class, periodId);
        BigDecimal companyGross = jdbc.queryForObject("""
                SELECT SUM(gross_amount) FROM pay_record
                 WHERE period_id = ? AND company_id = ? AND deleted = 0
                """, BigDecimal.class, periodId, TestDb.COMPANY_DEFAULT);

        asUser("SELF", selfEmployee, TestDb.DEPT_DEFAULT);
        PeriodDTO self = periodService.get(periodId);
        assertThat(self.totalGross()).isEqualByComparingTo(ownGross);
        assertThat(self.totalNet()).isEqualByComparingTo(ownGross);
        assertThat(self.employeeCount()).isEqualTo(1);
        // 绝不能等于全公司合计
        assertThat(self.totalGross()).isNotEqualByComparingTo(companyGross);

        // 列表接口同样收窄
        assertThat(periodService.list(TestDb.COMPANY_DEFAULT))
                .filteredOn(p -> p.id().equals(periodId))
                .singleElement()
                .satisfies(p -> assertThat(p.totalGross()).isEqualByComparingTo(ownGross));

        // 集团账号仍看期间表上的公司口径
        CurrentUser.set(ALL_SCOPE_USER);
        assertThat(periodService.get(periodId).totalGross()).isEqualByComparingTo(companyGross);
    }

    @Test
    void SELF用户访问他人人员详情返回403() {
        asUser("SELF", selfEmployee, TestDb.DEPT_DEFAULT);

        assertThatThrownBy(() -> employeeService.get(childDeptEmployee))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权");
        assertThatCode(() -> employeeService.get(selfEmployee)).doesNotThrowAnyException();
    }

    @Test
    void SELF用户不能改他人的核算记录() {
        // 解锁回编制中：受限账号连"未锁定期间"都不该看到，越权在状态判断之前拦截
        periodService.unlock(periodId, 2L, "数据范围用例");
        asUser("SELF", selfEmployee, TestDb.DEPT_DEFAULT);
        Long otherRecordId = jdbc.queryForObject(
                "SELECT id FROM pay_record WHERE period_id = ? AND emp_no = 'E003'", Long.class, periodId);

        assertThatThrownBy(() -> recordService.applyChangeset(periodId, new ChangesetReq(List.of(
                new ChangesetChange(otherRecordId, 0, List.of(new ChangesetItem("PERF_BONUS", "1.00")))))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void DEPT_SUB主管能看下级部门不能看其他部门() {
        asUser("DEPT_SUB", null, TestDb.DEPT_DEFAULT);

        assertThatCode(() -> enforcer.assertEmployeeVisible(selfEmployee)).doesNotThrowAnyException();
        assertThatCode(() -> enforcer.assertEmployeeVisible(childDeptEmployee)).doesNotThrowAnyException();
        assertThatThrownBy(() -> enforcer.assertEmployeeVisible(otherDeptEmployee))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void DEPT主管只看本部门() {
        asUser("DEPT", null, TestDb.DEPT_DEFAULT);

        assertThatCode(() -> enforcer.assertEmployeeVisible(selfEmployee)).doesNotThrowAnyException();
        assertThatThrownBy(() -> enforcer.assertEmployeeVisible(childDeptEmployee))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 跨主体访问一律403() {
        asUser("COMPANY", null, TestDb.DEPT_DEFAULT);

        assertThatThrownBy(() -> enforcer.assertCompanyVisible(TestDb.COMPANY_BEIJING))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权");
        assertThatThrownBy(() -> enforcer.assertEmployeeVisible(otherCompanyEmployee))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权");
        assertThatCode(() -> enforcer.assertCompanyVisible(TestDb.COMPANY_DEFAULT))
                .doesNotThrowAnyException();
    }

    @Test
    void ALL范围的集团HR不受限制() {
        assertThatCode(() -> enforcer.assertCompanyVisible(TestDb.COMPANY_BEIJING))
                .doesNotThrowAnyException();
        assertThatCode(() -> enforcer.assertEmployeeVisible(otherCompanyEmployee))
                .doesNotThrowAnyException();
    }

    @Test
    void 未登录时拒绝而不是当成无限制() {
        CurrentUser.clear();

        assertThatThrownBy(() -> enforcer.assertCompanyVisible(TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未认证");
    }

    /**
     * 防的是"静默空操作"：如果某个范围生成了空条件，调用方一旦漏写主体条件就变成全表可见。
     * 这里对五种范围都断言 SQL 谓词非空——ALL 也必须是显式的 {@code 1 = 1}。
     */
    @Test
    void 五种数据范围都会生成非空的SQL谓词() {
        for (String scope : List.of("ALL", "COMPANY", "DEPT_SUB", "DEPT", "SELF")) {
            asUser(scope, selfEmployee, TestDb.DEPT_DEFAULT);

            LambdaQueryWrapper<PayRecordEntity> recordFilter = Wrappers.lambdaQuery();
            enforcer.applyRecordFilter(recordFilter);
            assertThat(recordFilter.getSqlSegment())
                    .as("record filter for scope %s", scope)
                    .isNotBlank();

            LambdaQueryWrapper<EmpEmployeeEntity> employeeFilter = Wrappers.lambdaQuery();
            enforcer.applyEmployeeFilter(employeeFilter);
            assertThat(employeeFilter.getSqlSegment())
                    .as("employee filter for scope %s", scope)
                    .isNotBlank();
        }
    }

    @Test
    void ALL范围生成的是显式谓词而不是空条件() {
        asUser("ALL", null, TestDb.DEPT_DEFAULT);

        LambdaQueryWrapper<PayRecordEntity> recordFilter = Wrappers.lambdaQuery();
        enforcer.applyRecordFilter(recordFilter);

        assertThat(recordFilter.getSqlSegment()).isNotBlank().contains("1 = 1");
    }

    // ------------------------------------------------------------------

    private void asUser(String scope, Long employeeId, Long deptId) {
        // dept_id 由 DataScopeEnforcer 从 sys_user 读取，所以直接改用户所属部门
        jdbc.update("UPDATE sys_user SET dept_id = ? WHERE id = ?", deptId, USER);
        CurrentUser.set(new CurrentUser(USER, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:record:view", "payroll:record:edit"), scope, employeeId));
    }

    private Long employee(String empNo, long companyId, long deptId) {
        Long id = employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD", "11010119900101" + empNo.substring(2) + "0", null,
                "REGULAR", "P6", "工程师", deptId,
                LocalDate.of(2020, 1, 1), null, null, 1, null), companyId);
        compProfileService.save(new CompProfileSaveReq(null, id, LocalDate.of(2020, 1, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));
        return id;
    }
}
