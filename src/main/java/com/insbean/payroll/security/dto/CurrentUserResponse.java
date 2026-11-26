package com.insbean.payroll.security.dto;

import java.util.Set;

/** 契约 components.schemas.CurrentUser。 */
public record CurrentUserResponse(
        Long userId,
        String username,
        String realName,
        Long companyId,
        Long employeeId,
        String dataScope,
        Set<String> permissions
) {
}
