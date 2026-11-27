package com.insbean.payroll.admin.dto;

import java.util.List;

/** 契约 components.schemas.UserPage。total/current/size 是计数，必须是 JSON number。 */
public record UserPageDTO(List<UserDTO> records, int total, int current, int size) {
}
