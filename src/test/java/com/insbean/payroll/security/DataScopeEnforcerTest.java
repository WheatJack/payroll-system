package com.insbean.payroll.security;

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
    }

    @Test
    void SELF用户只能看到自己那一行工资() {
        asUser("SELF", selfEmployee, TestDb.DEPT_DEFAULT);

        assertThat(recordService.grid(periodId).rows())
                .extracting(GridRow::empNo)
                .containsExactly("E001");
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
