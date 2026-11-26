package com.insbean.payroll.admin.dto;

import java.util.List;

/** 契约 components.schemas.RoleGrantRequest：全量替换，传空数组即清空。 */
public record RoleGrantReq(List<String> permissions) {
}
