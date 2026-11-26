package com.insbean.payroll.importjob.dto;

import java.util.List;

/** 契约 components.schemas.ImportValidateResult。 */
public record ImportValidateResultDTO(
        Long batchId,
        int totalRows,
        int successRows,
        int failRows,
        String status,
        List<ImportErrorDTO> errors
) {
}
