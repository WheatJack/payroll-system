package com.insbean.payroll.employee.dto;

/** 人员分页查询条件。 */
public record EmployeeQuery(
        Integer current,
        Integer size,
        String keyword,
        Long deptId,
        String employeeType,
        Integer status
) {
    public int pageNo() {
        return current == null || current < 1 ? 1 : current;
    }

    public int pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 200);
    }
}
