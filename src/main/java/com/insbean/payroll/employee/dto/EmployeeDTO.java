package com.insbean.payroll.employee.dto;

import java.time.LocalDate;

/** 契约 components.schemas.Employee。只有掩码，永远不含证件号/手机号明文。 */
public record EmployeeDTO(
        Long id,
        Long companyId,
        String empNo,
        String name,
        Integer gender,
        String idType,
        String idNoMask,
        String mobileMask,
        String employeeType,
        String jobLevel,
        String positionName,
        Long deptId,
        LocalDate hireDate,
        LocalDate workStartDate,
        LocalDate leaveDate,
        Integer status,
        String remark
) {
}
