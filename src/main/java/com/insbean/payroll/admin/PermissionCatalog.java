package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.PermissionItemDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能权限点目录（契约 components.schemas.PermissionCode）。
 *
 * <p>权限点必须由后端给出而不是前端硬编码：否则新增权限点时前端要跟着发版，
 * 更危险的是前端可以"自造"权限点提交给授权接口。这里同时充当
 * {@code PUT /roles/{id}/grants} 的白名单。
 */
public final class PermissionCatalog {

    private static final List<PermissionItemDTO> ITEMS = List.of(
            new PermissionItemDTO("payroll:record:view", "查看核算", "PAYROLL"),
            new PermissionItemDTO("payroll:record:edit", "编辑核算", "PAYROLL"),
            new PermissionItemDTO("payroll:period:lock", "锁定期间", "PAYROLL"),
            new PermissionItemDTO("payroll:period:unlock", "解锁期间", "PAYROLL"),
            new PermissionItemDTO("payroll:import:submit", "导入工资数据", "PAYROLL"),
            new PermissionItemDTO("payroll:export", "导出报表", "PAYROLL"),
            new PermissionItemDTO("payroll:sensitive:view", "查看敏感字段", "PAYROLL"),
            new PermissionItemDTO("sys:user:view", "查看用户", "SYS"),
            new PermissionItemDTO("sys:user:edit", "编辑用户", "SYS"),
            new PermissionItemDTO("sys:role:view", "查看角色", "SYS"),
            new PermissionItemDTO("sys:role:edit", "编辑角色", "SYS"),
            new PermissionItemDTO("sys:role:grant:all", "授予集团数据范围", "SYS"),
            new PermissionItemDTO("sys:dept:edit", "编辑部门", "SYS"),
            new PermissionItemDTO("sys:company:edit", "编辑法人主体", "SYS"),
            new PermissionItemDTO("sys:config:view", "查看系统参数", "SYS"),
            new PermissionItemDTO("sys:config:edit", "编辑系统参数", "SYS"));

    private static final Set<String> CODES = ITEMS.stream()
            .map(PermissionItemDTO::code)
            .collect(LinkedHashSet::new, Set::add, Set::addAll);

    private PermissionCatalog() {
    }

    public static List<PermissionItemDTO> all() {
        return ITEMS;
    }

    public static boolean contains(String code) {
        return code != null && CODES.contains(code);
    }
}
