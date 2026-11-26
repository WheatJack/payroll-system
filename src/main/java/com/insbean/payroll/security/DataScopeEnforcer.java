package com.insbean.payroll.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.security.mapper.AuthMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 数据范围强制（详细设计 §4.7 第 2 层）。
 *
 * <p>功能权限只回答"你能不能做这件事"，数据范围回答"你能看到谁的数据"。
 * 只做前者的话，一个 data_scope=SELF 的普通员工只要拿到 payroll:record:view，
 * 就能直接 {@code GET /periods/{任意id}/grid} 把全公司工资表拉走——这是本系统最大的越权面。
 *
 * <p>因此所有"按客户端传入的 id 去取数据"的入口（期间、编制台、人员、薪酬档案、部门）
 * 都必须在 **Service 层**过这里，而不是在 Controller 层靠自觉。
 *
 * <p>两类 API：
 * <ul>
 *   <li>{@code assertXxxVisible(...)} —— 单条校验，越权抛 403；</li>
 *   <li>{@code applyXxxFilter(wrapper)} —— 把数据范围翻译成 SQL 条件，用于列表/报表下推，
 *       避免"先查全表再在内存里过滤"。</li>
 * </ul>
 */
@Component
public class DataScopeEnforcer {

    public static final String ALL = "ALL";
    public static final String COMPANY = "COMPANY";
    public static final String DEPT_SUB = "DEPT_SUB";
    public static final String DEPT = "DEPT";
    public static final String SELF = "SELF";

    private final EmployeeMapper employeeMapper;
    private final PeriodMapper periodMapper;
    private final DeptMapper deptMapper;
    private final AuthMapper authMapper;

    public DataScopeEnforcer(EmployeeMapper employeeMapper,
                             PeriodMapper periodMapper,
                             DeptMapper deptMapper,
                             AuthMapper authMapper) {
        this.employeeMapper = employeeMapper;
        this.periodMapper = periodMapper;
        this.deptMapper = deptMapper;
        this.authMapper = authMapper;
    }

    // ------------------------------------------------------------------ 单条校验

    public void assertCompanyVisible(Long companyId) {
        CurrentUser current = requireUser();
        if (ALL.equals(current.dataScope())) {
            return;
        }
        if (companyId == null || !companyId.equals(current.companyId())) {
            throw forbidden("无权访问该主体的数据");
        }
    }

    public void assertPeriodVisible(Long periodId) {
        PayPeriodEntity period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BizException(404, "工资期间不存在");
        }
        assertCompanyVisible(period.getCompanyId());
    }

    public void assertEmployeeVisible(Long employeeId) {
        CurrentUser current = requireUser();
        if (ALL.equals(current.dataScope())) {
            return;
        }
        EmpEmployeeEntity employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BizException(404, "人员不存在");
        }
        if (!employeeVisible(current, employee)) {
            throw forbidden("无权访问该人员数据");
        }
    }

    /** 核算记录快照自带 company_id / dept_id / employee_id，直接按快照判定，不必回查人员表。 */
    public void assertRecordVisible(PayRecordEntity record) {
        CurrentUser current = requireUser();
        if (ALL.equals(current.dataScope())) {
            return;
        }
        if (record == null) {
            throw new BizException(404, "核算记录不存在");
        }
        boolean visible = switch (current.dataScope()) {
            case COMPANY -> Objects.equals(record.getCompanyId(), current.companyId());
            case SELF -> current.employeeId() != null
                    && Objects.equals(record.getEmployeeId(), current.employeeId());
            case DEPT -> Objects.equals(record.getDeptId(), deptIdOf(current));
            case DEPT_SUB -> isInDeptSubtree(record.getDeptId(), deptIdOf(current));
            default -> false;
        };
        if (!visible) {
            throw forbidden("无权访问该核算记录");
        }
    }

    // ------------------------------------------------------------------ 查询条件下推

    /** 人员列表：把数据范围拼进查询条件（报表服务同样可以直接复用）。 */
    public void applyEmployeeFilter(LambdaQueryWrapper<EmpEmployeeEntity> wrapper) {
        String condition = employeeScopeSql(requireUser());
        if (condition != null) {
            wrapper.apply(condition);
        }
    }

    /** 核算记录列表：按快照列过滤，历史部门的归属不会因为人员调动而变化。 */
    public void applyRecordFilter(LambdaQueryWrapper<PayRecordEntity> wrapper) {
        CurrentUser current = requireUser();
        switch (current.dataScope()) {
            case ALL, COMPANY -> {
                // 主体级过滤由调用方按期间的 company_id 完成，这里不再叠加
            }
            case SELF -> wrapper.apply(current.employeeId() == null
                    ? "1 = 0"
                    : "employee_id = " + current.employeeId());
            case DEPT -> wrapper.apply("dept_id = " + deptIdOf(current));
            case DEPT_SUB -> wrapper.apply(deptSubtreeSql(deptIdOf(current)));
            default -> wrapper.apply("1 = 0");
        }
    }

    private boolean employeeVisible(CurrentUser current, EmpEmployeeEntity employee) {
        return switch (current.dataScope()) {
            case COMPANY -> Objects.equals(employee.getCompanyId(), current.companyId());
            case SELF -> current.employeeId() != null
                    && Objects.equals(employee.getId(), current.employeeId());
            case DEPT -> Objects.equals(employee.getDeptId(), deptIdOf(current));
            case DEPT_SUB -> isInDeptSubtree(employee.getDeptId(), deptIdOf(current));
            default -> false;
        };
    }

    /** 返回 null 表示无需追加条件（ALL）。 */
    private String employeeScopeSql(CurrentUser current) {
        return switch (current.dataScope()) {
            case ALL -> null;
            case COMPANY -> "company_id = " + current.companyId();
            case SELF -> current.employeeId() == null ? "1 = 0" : "id = " + current.employeeId();
            case DEPT -> "dept_id = " + deptIdOf(current);
            case DEPT_SUB -> deptSubtreeSql(deptIdOf(current));
            default -> "1 = 0";
        };
    }

    /**
     * DEPT_SUB：本部门及其所有下级。sys_dept.ancestors 是 ",1,2,3," 形式的祖先路径，
     * 因此"下级"等价于 ancestors 里含有 ",本部门id,"。
     */
    private static String deptSubtreeSql(Long deptId) {
        return "dept_id IN (SELECT id FROM sys_dept WHERE deleted = 0 AND (id = " + deptId
                + " OR ancestors LIKE '%," + deptId + ",%'))";
    }

    private boolean isInDeptSubtree(Long employeeDeptId, Long rootDeptId) {
        if (employeeDeptId == null || rootDeptId == null) {
            return false;
        }
        return employeeDeptId.equals(rootDeptId)
                || deptMapper.findSubtreeIds(rootDeptId).contains(employeeDeptId);
    }

    private Long deptIdOf(CurrentUser current) {
        Long deptId = authMapper.findDeptId(current.userId());
        return deptId == null ? -1L : deptId;
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }

    private static BizException forbidden(String message) {
        return new BizException(403, message);
    }
}
