package com.insbean.payroll.domain.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 计算一条核算记录所需的全部输入。
 *
 * @param payableDays 当月实际计薪天数；为 null 表示全月在岗，不做折算
 * @param itemDefs    工资项定义，键为科目编码
 * @param fixedItems  薪酬档案带出的固定津贴（不含基本工资，基本工资走 baseSalary）
 * @param variableItems 已导入或手工录入的变动金额，原样计入
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
        Map<String, BigDecimal> variableItems
) {
}
