package com.insbean.payroll.org.dto;

/** 契约 components.schemas.Dept：扁平节点，前端按 parentId/ancestors 自行组树。 */
public record DeptDTO(
        Long id,
        Long companyId,
        Long parentId,
        String deptCode,
        String deptName,
        String ancestors,
        Integer sortNo,
        Integer status
) {
}
