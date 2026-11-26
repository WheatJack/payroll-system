package com.insbean.payroll.item.dto;

/** 契约 components.schemas.PayItem。方向用 1/2 表示，与契约一致。 */
public record PayItemDefDTO(
        Long id,
        Long companyId,
        String itemCode,
        String itemName,
        String category,
        Integer direction,
        String dataType,
        Integer precisionScale,
        Boolean includeInGross,
        Boolean editable,
        Boolean importable,
        Integer sortNo,
        Integer status
) {
}
