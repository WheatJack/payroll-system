package com.insbean.payroll.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 契约 components.schemas.User。密码哈希永不出现。 */
public record UserDTO(
        Long id,
        Long companyId,
        String username,
        String realName,
        Long employeeId,
        Long deptId,
        String mobileMask,
        Integer status,
        LocalDateTime lastLoginAt,
        List<Long> roleIds,
        List<String> permissions,
        String dataScope
) {
}
