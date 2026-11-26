package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_role")
public class SysRoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String roleCode;
    private String roleName;
    private String dataScope;
    private Integer status;
    private Integer deleted;
}
