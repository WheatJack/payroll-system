package com.insbean.payroll.admin.dto;

/** 契约 components.schemas.RoleUpdateRequest；权限点走 /roles/{id}/grants。 */
public record RoleUpdateReq(
        String roleCode,
        String roleName,
        String dataScope,
        String remark,
        Integer status
) {
}
