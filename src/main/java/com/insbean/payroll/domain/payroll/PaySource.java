package com.insbean.payroll.domain.payroll;

/** 明细项的来源，用于重跑生成时决定"能否覆盖既有值"。 */
public enum PaySource {
    FIXED,
    VARIABLE,
    COMPUTED,
    /** 编制台手工修改；重新生成时必须原样保留，不能被计算值覆盖。 */
    MANUAL
}
