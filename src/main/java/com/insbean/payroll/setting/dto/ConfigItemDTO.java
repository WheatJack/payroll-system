package com.insbean.payroll.setting.dto;

/** 契约 components.schemas.ConfigItem。 */
public record ConfigItemDTO(
        String key,
        String value,
        String valueType,
        String description,
        boolean editable
) {
}
