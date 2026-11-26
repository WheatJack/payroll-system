package com.insbean.payroll.record.dto;

import java.util.List;

/** 契约 components.schemas.ChangesetResult：返回受影响行及其新版本号、新合计。 */
public record ChangesetResult(int updated, List<GridRow> rows) {
}
