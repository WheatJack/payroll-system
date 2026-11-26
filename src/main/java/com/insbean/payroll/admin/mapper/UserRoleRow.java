package com.insbean.payroll.admin.mapper;

import lombok.Data;

/** sys_user_role 的一行关联，用于批量组装用户列表。 */
@Data
public class UserRoleRow {
    private Long userId;
    private Long roleId;
}
