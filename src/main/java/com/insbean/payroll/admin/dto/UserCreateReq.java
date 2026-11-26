package com.insbean.payroll.admin.dto;

import java.util.List;

/** 契约 components.schemas.UserCreateRequest。 */
public record UserCreateReq(
        Long companyId,
        String username,
        String password,
        String realName,
        Long employeeId,
        Long deptId,
        Integer status,
        List<Long> roleIds
) {
}
