package com.insbean.payroll.domain.payroll;

public record PayItemDef(
        String code,
        String name,
        PayCategory category,
        PayDirection direction
) {
}
