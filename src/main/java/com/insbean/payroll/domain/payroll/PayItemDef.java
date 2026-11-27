package com.insbean.payroll.domain.payroll;

/**
 * 工资项的计算域定义。
 *
 * @param precisionScale  该科目自己的小数位（字典 precision_scale）。计算时按它舍入一次，
 *                        不能统一按 2 位——否则 4 位科目在"重新生成"时会被静默改钱。
 * @param includeInGross 是否计入应发合计（字典 include_in_gross）。为 false 的收入项
 *                        仍出现在明细里，但不参与 gross/net。
 */
public record PayItemDef(
        String code,
        String name,
        PayCategory category,
        PayDirection direction,
        int precisionScale,
        boolean includeInGross
) {
}
