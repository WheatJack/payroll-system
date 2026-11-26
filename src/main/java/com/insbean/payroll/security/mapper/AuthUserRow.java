package com.insbean.payroll.security.mapper;

import lombok.Data;

/** sys_user 的认证视图。只包含认证/鉴权需要的列，不加载任何敏感字段。 */
@Data
public class AuthUserRow {

    private Long id;
    private Long companyId;
    private String username;
    private String passwordHash;
    private String realName;
    private Long employeeId;
    private Long deptId;
    private Integer status;
}
