package com.insbean.payroll.employee.dto;

import java.time.LocalDate;

/**
 * 契约 components.schemas.Employee。只有掩码，永远不含证件号/手机号明文。
 *
 * <p>{@code seniorityYears} / {@code socialSeniorityYears} 是**派生值不落库**：
 * 按查询当日与 hire_date / work_start_date 的整年差计算，供 HR 在人员页直接查看，
 * 不依赖是否生成过工资表。work_start_date 为空时社会工龄为 null。
 *
 * <p>**类型是两位小数的字符串**（契约 pattern {@code ^[0-9]{1,3}\.[0-9]{2}$}），
 * 不是数字：JacksonConfig 只把 Long/BigDecimal 转字符串，int 会原样输出数字，
 * 前端按契约调用 {@code value.trim()} 会 TypeError 把整页表格打白。
 * 与编制台 {@code GridRow.seniorityYears} 保持同一类型。
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
        String seniorityYears,
        String socialSeniorityYears,
        Integer status,
        String remark
) {
}
