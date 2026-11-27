package com.insbean.payroll.setting.dto;

/**
 * 契约 components.schemas.ConfigItem。
 *
 * @param warning 非空表示该参数当前值有业务风险（如节假日为空会导致不足月折算偏多），
 *                前端应展示警示样式；正常为 null。
 */
public record ConfigItemDTO(
        String key,
        String value,
        String valueType,
        String description,
        boolean editable,
        String warning
) {
}
