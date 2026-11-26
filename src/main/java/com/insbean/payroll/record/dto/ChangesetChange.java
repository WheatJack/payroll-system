package com.insbean.payroll.record.dto;

import java.util.List;

/** 契约 components.schemas.ChangesetChange：一行记录的一批改动 + 提交时的版本号。 */
public record ChangesetChange(Long recordId, Integer version, List<ChangesetItem> items) {
}
