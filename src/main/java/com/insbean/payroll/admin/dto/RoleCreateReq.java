package com.insbean.payroll.admin.dto;

import java.util.List;

/** 契约 components.schemas.RoleCreateRequest。 */
public record RoleCreateReq(
        Long companyId,
        String roleCode,
        String roleName,
        String dataScope,
        String remark,
        Integer status,
        List<String> permissions
) {
}
