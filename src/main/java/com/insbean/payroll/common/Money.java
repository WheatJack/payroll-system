package com.insbean.payroll.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额格式化的唯一出口。
 *
 * <p>曾经 PeriodService / RecordService / ReportService 各有一套：
 * 有的强制 2 位、有的保留有效位数，报表对 SUM 整体舍入后再与前端逐部门相加
 * 就会出现分位差。统一为「至少 2 位，超过 2 位的有效小数原样保留」：
 * 明细是 4 位就展示 4 位，合计等于明细/分组的精确加总，不做二次舍入。
 */
public final class Money {

    public static final int DISPLAY_SCALE = 2;

    private Money() {
    }

    public static String format(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        int effective = Math.max(DISPLAY_SCALE, Math.max(0, value.stripTrailingZeros().scale()));
        return value.setScale(effective, RoundingMode.HALF_UP).toPlainString();
    }

    public static BigDecimal scaled(BigDecimal value) {
        if (value == null) {
            return new BigDecimal("0.00");
        }
        int effective = Math.max(DISPLAY_SCALE, Math.max(0, value.stripTrailingZeros().scale()));
        return value.setScale(effective, RoundingMode.HALF_UP);
    }
}
