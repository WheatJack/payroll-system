package com.insbean.payroll.period.dto;

/** 契约 components.schemas.PeriodCreateRequest。companyId 缺省取当前用户所属主体。 */
public record PeriodCreateReq(
        Long companyId,
        String periodYm
) {
}
