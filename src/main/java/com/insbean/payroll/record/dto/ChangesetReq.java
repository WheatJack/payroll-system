package com.insbean.payroll.record.dto;

import java.util.List;

/** 契约 components.schemas.ChangesetRequest。 */
public record ChangesetReq(List<ChangesetChange> changes) {
}
