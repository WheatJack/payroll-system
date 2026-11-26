package com.insbean.payroll.domain.payroll;

/** 明细项的来源，用于重跑生成时决定"能否覆盖既有值"。 */
public enum PaySource {
    FIXED,
    VARIABLE,
    COMPUTED
}
