package com.insbean.payroll.record.dto;

import java.util.Map;

/**
 * 契约 components.schemas.GridRow。
 *
 * <p>金额与工龄都是字符串：前端用 decimal.js 运算，任何一处落回 JSON number
 * 都会在 0.1 + 0.2 上丢分，而薪酬系统的一分钱差异就是线上事故。
 */
public record GridRow(
        Long recordId,
        Integer version,
        Long employeeId,
        String empNo,
        String empName,
        Long deptId,
        String deptName,
        String employeeType,
        String jobLevel,
        String seniorityYears,
        String grossAmount,
        String deductionAmount,
        String netAmount,
        Map<String, String> items
) {
}
