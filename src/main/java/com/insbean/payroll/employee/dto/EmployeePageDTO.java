package com.insbean.payroll.employee.dto;

import java.util.List;

/** 契约 components.schemas.EmployeePage。 */
public record EmployeePageDTO(
        List<EmployeeDTO> records,
        long total,
        int current,
        int size
) {
}
