package com.insbean.payroll.employee.dto;

import java.time.LocalDate;

/**
 * 契约 components.schemas.Employee。只有掩码，永远不含证件号/手机号明文。
 *
 * <p>{@code seniorityYears} / {@code socialSeniorityYears} 是**派生值不落库**：
 * 按查询当日与 hire_date / work_start_date 的整年差计算，供 HR 在人员页直接查看，
 * 不依赖是否生成过工资表。work_start_date 为空时社会工龄为 null。
 */
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
        String deptName,
        LocalDate hireDate,
        LocalDate workStartDate,
        LocalDate leaveDate,
        Integer seniorityYears,
        Integer socialSeniorityYears,
        Integer status,
        String remark
) {
}
