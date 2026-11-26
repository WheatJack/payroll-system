package com.insbean.payroll.admin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_role 的系统管理视图。
 *
 * <p>为什么不直接用 {@code domain.entity.SysRoleEntity}：那个实体缺少 remark 列，
 * 而契约的 Role 必须返回备注。这里补齐成一个完整的行对象，避免为了一列去改动核算域的实体。
 */
@Data
@TableName("sys_role")
public class RoleRow {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String roleCode;
    private String roleName;
    private String dataScope;
    private String remark;
    private Integer status;
    private Integer deleted;
}
