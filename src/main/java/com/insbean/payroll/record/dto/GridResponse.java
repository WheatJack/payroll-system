package com.insbean.payroll.record.dto;

import java.util.List;

/** 契约 components.schemas.GridResponse：全量、不分页。 */
public record GridResponse(
        GridPeriod period,
        List<GridColumn> columns,
        List<GridRow> rows,
        int total
) {
}
