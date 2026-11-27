package com.insbean.payroll.employee.dto;

import java.util.List;

/**
 * 契约 components.schemas.EmployeePage。
 *
 * <p>{@code total} 是计数不是 ID：必须序列化成 JSON number。
 * JacksonConfig 把所有 Long 转字符串是为了保护 ID 精度，但分页总数是字符串时
 * 前端 ElPagination 会直接报错、整块不渲染。
 */
public record EmployeePageDTO(
        List<EmployeeDTO> records,
        int total,
        int current,
        int size
) {
}
