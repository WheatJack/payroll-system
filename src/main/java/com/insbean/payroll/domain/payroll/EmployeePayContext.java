package com.insbean.payroll.domain.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 计算一条核算记录所需的全部输入。
 *
 * @param payableDays        当月实际计薪天数；为 null 表示全月在岗，不做折算
 * @param itemDefs           工资项定义，键为科目编码
 * @param fixedItems         薪酬档案带出的固定津贴（不含基本工资，基本工资走 baseSalary）
 * @param variableItems      已导入或手工录入的变动金额，原样计入
 * @param monthlyPayableDays 期间计薪天数参数（pay_period.hpd），为 null 时用
 *                           {@link PayrollRules#MONTHLY_PAYABLE_DAYS}（21.75）。
 *                           做成参数而不是常量：不同主体/期间可以配不同的计薪天数，
 *                           硬编码会让「期间参数」形同虚设。
 */
public record EmployeePayContext(
        Long employeeId,
        String empNo,
        String empName,
        BigDecimal baseSalary,
        LocalDate hireDate,
        LocalDate leaveDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer payableDays,
        Map<String, PayItemDef> itemDefs,
        Map<String, BigDecimal> fixedItems,
        Map<String, BigDecimal> variableItems,
        BigDecimal monthlyPayableDays
) {

    /** 兼容不关心期间参数的调用方（默认 21.75）。 */
    public EmployeePayContext(Long employeeId,
                              String empNo,
                              String empName,
                              BigDecimal baseSalary,
                              LocalDate hireDate,
                              LocalDate leaveDate,
                              LocalDate periodStart,
                              LocalDate periodEnd,
                              Integer payableDays,
                              Map<String, PayItemDef> itemDefs,
                              Map<String, BigDecimal> fixedItems,
                              Map<String, BigDecimal> variableItems) {
        this(employeeId, empNo, empName, baseSalary, hireDate, leaveDate, periodStart, periodEnd,
                payableDays, itemDefs, fixedItems, variableItems, null);
    }

    public BigDecimal payableDaysDivisor() {
        return monthlyPayableDays != null && monthlyPayableDays.signum() > 0
                ? monthlyPayableDays
                : PayrollRules.MONTHLY_PAYABLE_DAYS;
    }
}
