package com.insbean.payroll.admin.mapper;

import lombok.Data;

/** 用户某个角色的数据范围，用于计算"最宽范围"。 */
@Data
public class UserScopeRow {
    private Long userId;
    private String dataScope;
}
