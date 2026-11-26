package com.insbean.payroll.domain.payroll;

/** 工资项的三分类，决定该科目由谁产生、能否被人工修改。 */
public enum PayCategory {
    /** 固定项：从薪酬档案带出 */
    FIXED,
    /** 变动项：导入或手工录入的金额 */
    VARIABLE,
    /** 计算项：由其他项推导，不允许直接编辑 */
    COMPUTED
}
