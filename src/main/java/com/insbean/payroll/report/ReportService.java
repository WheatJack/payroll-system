package com.insbean.payroll.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.report.dto.AnnualReportRow;
import com.insbean.payroll.report.dto.MonthlyReportRow;
import com.insbean.payroll.report.dto.ReportQuery;
import com.insbean.payroll.report.mapper.ReportMapper;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 月度 / 年度汇总（契约 /reports/monthly、/reports/annual）。
 *
 * <p>冻结口径：
 * <ul>
 *   <li>只统计 {@code LOCKED}/{@code ARCHIVED} 期间——编制中的数字随时会变，进报表就是假数；</li>
 *   <li>年度按归属期间（{@code pay_record.period_ym}）归集，13 薪算在它归属的月份；</li>
 *   <li>employeeCount 是组内去重人数，绝不等于各月相加；</li>
 *   <li>所有查询都过 {@link DataScopeEnforcer}，包括带 companyId 的调用。</li>
 * </ul>
 */
@Service
public class ReportService {

    /** 只有已锁定与已归档的期间才进报表。 */
    static final String LOCKED_PERIODS_SQL =
            "SELECT id FROM pay_period WHERE status IN ('LOCKED', 'ARCHIVED')";

    private static final String PERIOD_YM_PATTERN = "^[0-9]{4}-(0[1-9]|1[0-2])$";
    private static final String YEAR_PATTERN = "^[0-9]{4}$";

    private final ReportMapper reportMapper;
    private final DataScopeEnforcer dataScope;

    public ReportService(ReportMapper reportMapper, DataScopeEnforcer dataScope) {
        this.reportMapper = reportMapper;
        this.dataScope = dataScope;
    }

    public List<MonthlyReportRow> monthly(ReportQuery query) {
        String periodYm = query == null ? null : query.periodYm();
        if (periodYm == null || !periodYm.matches(PERIOD_YM_PATTERN)) {
            throw new BizException(400, "periodYm 格式必须为 YYYY-MM：" + periodYm);
        }
        CurrentUser me = requireUser();
        Long companyId = query.companyId();
        if (companyId != null) {
            dataScope.assertCompanyVisible(companyId);
        }

        LambdaQueryWrapper<PayRecordEntity> wrapper = lockedRecords();
        wrapper.eq(PayRecordEntity::getPeriodYm, periodYm);
        if (companyId != null) {
            wrapper.eq(PayRecordEntity::getCompanyId, companyId);
        }
        if (query.deptId() != null) {
            wrapper.eq(PayRecordEntity::getDeptId, query.deptId());
        }
        wrapper.groupBy(PayRecordEntity::getCompanyId, PayRecordEntity::getPeriodYm, PayRecordEntity::getDeptId)
                .orderByAsc(PayRecordEntity::getCompanyId)
                .orderByAsc(PayRecordEntity::getDeptId);
        dataScope.applyRecordFilter(wrapper);

        return reportMapper.monthly(wrapper).stream()
                .map(row -> new MonthlyReportRow(
                        asLong(row.get("company_id")),
                        String.valueOf(row.get("period_ym")),
                        asLong(row.get("dept_id")),
                        (String) row.get("dept_name"),
                        asInt(row.get("employee_count")),
                        money(row.get("gross_amount")),
                        money(row.get("deduction_amount")),
                        money(row.get("net_amount"))))
                .toList();
    }

    public List<AnnualReportRow> annual(ReportQuery query) {
        String year = query == null ? null : query.year();
        if (year == null || !year.matches(YEAR_PATTERN)) {
            throw new BizException(400, "year 格式必须为 YYYY：" + year);
        }
        String groupBy = normalizeGroupBy(query.groupBy());
        Long companyId = query.companyId();

        CurrentUser me = requireUser();
        if (companyId != null) {
            dataScope.assertCompanyVisible(companyId);
        }
        // 按部门汇总时若操作者能看多个主体，必须指定主体，否则同名部门会被合并加总
        if ("DEPT".equals(groupBy) && companyId == null
                && DataScopeEnforcer.ALL.equals(me.dataScope())) {
            throw new BizException(400, "按部门汇总年度报表时 companyId 必填（当前账号可见多个主体）");
        }

        LambdaQueryWrapper<PayRecordEntity> wrapper = lockedRecords();
        wrapper.ge(PayRecordEntity::getPeriodYm, year + "-01")
                .le(PayRecordEntity::getPeriodYm, year + "-12");
        if (companyId != null) {
            wrapper.eq(PayRecordEntity::getCompanyId, companyId);
        }
        if (query.deptId() != null) {
            wrapper.eq(PayRecordEntity::getDeptId, query.deptId());
        }
        dataScope.applyRecordFilter(wrapper);

        if ("EMP".equals(groupBy)) {
            wrapper.groupBy(PayRecordEntity::getEmployeeId)
                    .orderByAsc(PayRecordEntity::getEmployeeId);
            return reportMapper.annualByEmployee(wrapper).stream()
                    .map(row -> new AnnualReportRow(
                            year, "EMP", null, null,
                            asLong(row.get("employee_id")),
                            (String) row.get("emp_no"),
                            (String) row.get("emp_name"),
                            asInt(row.get("employee_count")),
                            money(row.get("gross_amount")),
                            money(row.get("deduction_amount")),
                            money(row.get("net_amount"))))
                    .toList();
        }

        wrapper.groupBy(PayRecordEntity::getCompanyId, PayRecordEntity::getDeptId)
                .orderByAsc(PayRecordEntity::getCompanyId)
                .orderByAsc(PayRecordEntity::getDeptId);
        return reportMapper.annualByDept(wrapper).stream()
                .map(row -> new AnnualReportRow(
                        year, "DEPT",
                        asLong(row.get("dept_id")),
                        (String) row.get("dept_name"),
                        null, null, null,
                        asInt(row.get("employee_count")),
                        money(row.get("gross_amount")),
                        money(row.get("deduction_amount")),
                        money(row.get("net_amount"))))
                .toList();
    }

    /** 只统计已锁定/已归档期间的有效记录；永远显式写出 deleted=0，自定义 SQL 不会自动加逻辑删除条件。 */
    private LambdaQueryWrapper<PayRecordEntity> lockedRecords() {
        return Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getDeleted, 0)
                .inSql(PayRecordEntity::getPeriodId, LOCKED_PERIODS_SQL);
    }

    /** 契约只定义了 EMP/DEPT；任务描述里写作 EMPLOYEE，这里做兼容归一，输出仍是 EMP。 */
    static String normalizeGroupBy(String groupBy) {
        if (groupBy == null) {
            throw new BizException(400, "groupBy 必填");
        }
        return switch (groupBy.trim().toUpperCase()) {
            case "EMP", "EMPLOYEE" -> "EMP";
            case "DEPT" -> "DEPT";
            default -> throw new BizException(400, "groupBy 只能是 EMP 或 DEPT：" + groupBy);
        };
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return new BigDecimal("0.00");
        }
        BigDecimal amount = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static int asInt(Object value) {
        return value == null ? 0 : Integer.parseInt(value.toString());
    }
}
