package com.insbean.payroll.admin.dto;

import java.util.List;

/** 契约 components.schemas.UserUpdateRequest。username/companyId 不可修改。 */
public record UserUpdateReq(
        String realName,
        Long employeeId,
        Long deptId,
        Integer status,
        List<Long> roleIds
) {
}
