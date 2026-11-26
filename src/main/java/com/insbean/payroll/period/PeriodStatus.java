package com.insbean.payroll.period;

/**
 * 期间状态机（详细设计 §4.6）：
 * <pre>
 *   DRAFT ──生成──> EDITING ──锁定──> LOCKED ──归档──> ARCHIVED
 *                      ^                  |
 *                      └──── 解锁（需原因）┘
 * </pre>
 * ARCHIVED 是终态，不可解锁、不可写入。
 */
public enum PeriodStatus {

    DRAFT,
    EDITING,
    LOCKED,
    ARCHIVED;

    public static PeriodStatus of(String value) {
        return value == null ? DRAFT : PeriodStatus.valueOf(value);
    }
}
