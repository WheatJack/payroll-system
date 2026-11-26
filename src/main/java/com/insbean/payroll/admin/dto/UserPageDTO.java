package com.insbean.payroll.admin.dto;

import java.util.List;

/** 契约 components.schemas.UserPage。 */
public record UserPageDTO(List<UserDTO> records, long total, long current, long size) {
}
