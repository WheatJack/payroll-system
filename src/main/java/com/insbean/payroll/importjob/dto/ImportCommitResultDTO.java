package com.insbean.payroll.importjob.dto;

/** 契约 components.schemas.ImportCommitResult。 */
public record ImportCommitResultDTO(Long batchId, String status, int successRows, int failRows) {
}
