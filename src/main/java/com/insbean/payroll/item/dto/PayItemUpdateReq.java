package com.insbean.payroll.item.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 契约 components.schemas.PayItemUpdateRequest。null 表示不修改。 */
public record PayItemUpdateReq(
        @Size(max = 64) String itemName,
        @Pattern(regexp = "FIXED|VARIABLE|COMPUTED") String category,
        @Min(1) @Max(2) Integer direction,
        @Pattern(regexp = "AMOUNT|HOURS|DAYS|RATE") String dataType,
        Integer precisionScale,
        Boolean includeInGross,
        Boolean editable,
        Boolean importable,
        Integer sortNo,
        Integer status
) {
}
