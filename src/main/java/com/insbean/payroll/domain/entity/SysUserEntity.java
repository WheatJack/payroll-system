package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String username;
    private String passwordHash;
    private String realName;
    private Long employeeId;
    private Long deptId;
    private String mobileMask;
    private Integer status;
    /** 无状态 token 版本号：重置密码时 +1，旧 token 立即失效。 */
    private Integer tokenVersion;
    private LocalDateTime lastLoginAt;
    private Integer deleted;
}
