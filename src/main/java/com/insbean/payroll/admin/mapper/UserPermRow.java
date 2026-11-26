package com.insbean.payroll.admin.mapper;

import lombok.Data;

/** 用户经角色合并后的一个权限点。 */
@Data
public class UserPermRow {
    private Long userId;
    private String permCode;
}
