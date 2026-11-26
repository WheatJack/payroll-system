package com.insbean.payroll.common;

import java.util.List;

/**
 * 乐观锁版本冲突。
 *
 * <p>语义与普通 BizException 不同：它不是"请求参数有问题"，而是"别人已经改过了"，
 * 前端必须拿到冲突的 recordId 清单来高亮具体的行，因此单独建类型，
 * 由 {@code GlobalExceptionHandler} 映射成 HTTP 409 + {@code data.conflicts}。
 */
public class VersionConflictException extends RuntimeException {

    private final List<Long> recordIds;

    public VersionConflictException(List<Long> recordIds) {
        super("数据已被他人修改，请刷新");
        this.recordIds = recordIds == null ? List.of() : List.copyOf(recordIds);
    }

    public List<Long> getRecordIds() {
        return recordIds;
    }
}
