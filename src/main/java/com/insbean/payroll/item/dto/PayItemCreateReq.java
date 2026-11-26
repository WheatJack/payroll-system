package com.insbean.payroll.item.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 契约 components.schemas.PayItemCreateRequest。 */
public record PayItemCreateReq(
        Long companyId,
        @NotBlank @Size(max = 32) String itemCode,
        @NotBlank @Size(max = 64) String itemName,
        @NotBlank @Pattern(regexp = "FIXED|VARIABLE|COMPUTED") String category,
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
