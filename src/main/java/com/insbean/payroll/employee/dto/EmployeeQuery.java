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

    /** 上限 500：2000 人规模下前端确实需要一次拉多一点；超过上限按 500 返回（不静默截到 200）。 */
    public int pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 500);
    }
}
