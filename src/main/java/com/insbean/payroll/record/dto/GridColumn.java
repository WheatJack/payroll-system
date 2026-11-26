package com.insbean.payroll.record.dto;

/** 契约 components.schemas.GridColumn：工资表动态列，由工资项字典驱动。 */
public record GridColumn(
        String itemCode,
        String itemName,
        String category,
        Integer direction,
        Integer scale,
        Boolean editable
) {
}
