package com.insbean.payroll.admin.dto;

/** 契约 components.schemas.DeptCreateRequest；ancestors 由后端生成。 */
public record DeptCreateReq(
        Long companyId,
        Long parentId,
        String deptCode,
        String deptName,
        Integer sortNo,
        Integer status
) {
}
