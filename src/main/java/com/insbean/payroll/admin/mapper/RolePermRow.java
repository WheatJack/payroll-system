package com.insbean.payroll.admin.mapper;

import lombok.Data;

/** 某个角色的一条授权，用于批量组装角色列表。 */
@Data
public class RolePermRow {
    private Long roleId;
    private String permCode;
}
