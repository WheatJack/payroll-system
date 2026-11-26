package com.insbean.payroll.importjob;

/**
 * 提交被拒绝（409）。
 *
 * <p>预校验存在失败行时必须把 {@code failRows} 放进响应体（契约 ResultImportCommitConflict），
 * 普通 BizException 的 data 恒为 null，所以单独建类型交给 Controller 组装。
 */
public class ImportConflictException extends RuntimeException {

    private final Long batchId;
    private final int failRows;

    public ImportConflictException(Long batchId, int failRows) {
        super("预校验存在 " + failRows + " 行失败，禁止提交");
        this.batchId = batchId;
        this.failRows = failRows;
    }

    public Long getBatchId() {
        return batchId;
    }

    public int getFailRows() {
        return failRows;
    }
}
