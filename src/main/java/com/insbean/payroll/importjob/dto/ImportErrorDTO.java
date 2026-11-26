package com.insbean.payroll.importjob.dto;

/** 契约 components.schemas.ImportError。 */
public record ImportErrorDTO(
        Integer rowNo,
        String columnName,
        String rawValue,
        String errorCode,
        String errorMsg
) {
}
