package com.insbean.payroll.admin.dto;

/** 契约 components.schemas.DeptUpdateRequest；companyId 不可修改，ancestors 由后端重算。 */
public record DeptUpdateReq(
        Long parentId,
        String deptCode,
        String deptName,
        Integer sortNo,
        Integer status
) {
}
