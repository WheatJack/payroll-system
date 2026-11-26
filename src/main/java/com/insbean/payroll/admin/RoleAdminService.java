package com.insbean.payroll.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.admin.dto.RoleCreateReq;
import com.insbean.payroll.admin.dto.RoleDTO;
import com.insbean.payroll.admin.dto.RoleGrantReq;
import com.insbean.payroll.admin.dto.RoleUpdateReq;
import com.insbean.payroll.admin.mapper.RoleAdminMapper;
import com.insbean.payroll.admin.mapper.RolePermRow;
import com.insbean.payroll.admin.mapper.UserAdminMapper;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色与授权（契约 /roles、/roles/{id}/grants）。
 *
 * <p>防自我提权是这里的核心职责，见 {@link ScopeGuard}。
 * 授权接口是"全量替换"语义：先删后插，事务内完成，避免"只加不减"导致权限只增不减。
 */
@Service
public class RoleAdminService {

    private static final List<String> VALID_SCOPES =
            List.of(DataScopeEnforcer.ALL, DataScopeEnforcer.COMPANY,
                    DataScopeEnforcer.DEPT_SUB, DataScopeEnforcer.DEPT, DataScopeEnforcer.SELF);

    private final RoleAdminMapper roleMapper;
    private final UserAdminMapper userMapper;
    private final AuditService auditService;
    private final DataScopeEnforcer dataScope;

    public RoleAdminService(RoleAdminMapper roleMapper, UserAdminMapper userMapper,
                            AuditService auditService, DataScopeEnforcer dataScope) {
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.dataScope = dataScope;
    }

    // ------------------------------------------------------------------ 查询

    public List<RoleDTO> list(Long companyId, Integer status) {
        CurrentUser me = requireUser();
        if (companyId != null) {
            dataScope.assertCompanyVisible(companyId);
        }
        var query = Wrappers.<RoleRow>lambdaQuery()
                .eq(companyId != null, RoleRow::getCompanyId, companyId)
                .eq(status != null, RoleRow::getStatus, status)
                .orderByAsc(RoleRow::getId);
        if (!DataScopeEnforcer.ALL.equals(me.dataScope())) {
            query.eq(RoleRow::getCompanyId, me.companyId());
        }
        List<RoleRow> roles = roleMapper.selectList(query);
        Map<Long, List<String>> perms = permissionsByRole(roles);
        return roles.stream().map(r -> toDto(r, perms.getOrDefault(r.getId(), List.of()))).toList();
    }

    // ------------------------------------------------------------------ 新增 / 修改 / 删除

    @Transactional
    public RoleDTO create(RoleCreateReq req) {
        CurrentUser me = requireUser();
        if (req == null || req.companyId() == null) {
            throw new BizException(400, "companyId 必填");
        }
        dataScope.assertCompanyVisible(req.companyId());
        requireText(req.roleCode(), "角色编码不能为空");
        requireText(req.roleName(), "角色名称不能为空");
        String scope = requireScope(req.dataScope());

        // 新角色的范围不得超出操作者；授予 ALL 还需 sys:role:grant:all
        ScopeGuard.assertScopeNotWider(scope, me);
        ScopeGuard.assertAllGrantAllowed(scope, null, me);

        List<String> permissions = validatePermissionCodes(req.permissions());
        ScopeGuard.assertPermissionsWithinOperator(permissions, me);

        if (roleMapper.countByCode(req.companyId(), req.roleCode().trim()) > 0) {
            throw new BizException(409, "角色编码已存在：" + req.roleCode());
        }

        RoleRow entity = new RoleRow();
        entity.setCompanyId(req.companyId());
        entity.setRoleCode(req.roleCode().trim());
        entity.setRoleName(req.roleName().trim());
        entity.setDataScope(scope);
        entity.setRemark(req.remark());
        entity.setStatus(req.status() == null ? 1 : req.status());
        entity.setDeleted(0);
        try {
            roleMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "角色编码已存在：" + req.roleCode());
        }
        replaceGrants(entity.getId(), permissions);

        auditService.record("ROLE", entity.getId(), "CREATE", "roleCode", null, entity.getRoleCode());
        return toDto(entity, permissions);
    }

    @Transactional
    public RoleDTO update(Long id, RoleUpdateReq req) {
        CurrentUser me = requireUser();
        int adminsBefore = userMapper.countActiveAdmins();
        RoleRow entity = requireRole(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());

        if (req.roleCode() != null) {
            requireText(req.roleCode(), "角色编码不能为空");
            if (!req.roleCode().trim().equals(entity.getRoleCode())) {
                if (roleMapper.countByCodeExcluding(entity.getCompanyId(), req.roleCode().trim(), id) > 0) {
                    throw new BizException(409, "角色编码已存在：" + req.roleCode());
                }
                entity.setRoleCode(req.roleCode().trim());
            }
        }
        if (req.roleName() != null) {
            requireText(req.roleName(), "角色名称不能为空");
            entity.setRoleName(req.roleName().trim());
        }
        if (req.dataScope() != null && !req.dataScope().equals(entity.getDataScope())) {
            String scope = requireScope(req.dataScope());
            ScopeGuard.assertScopeNotWider(scope, me);
            ScopeGuard.assertAllGrantAllowed(scope, entity.getDataScope(), me);
            entity.setDataScope(scope);
        }
        if (req.remark() != null) {
            entity.setRemark(req.remark());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        roleMapper.updateById(entity);
        assertAdminSurvives(adminsBefore);

        auditService.record("ROLE", id, "UPDATE", "dataScope",
                null, entity.getDataScope());
        return toDto(entity, roleMapper.findPermissions(id));
    }

    @Transactional
    public void delete(Long id) {
        RoleRow entity = requireRole(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        int adminsBefore = userMapper.countActiveAdmins();
        if (roleMapper.countLinkedUsers(id) > 0) {
            throw new BizException(409, "仍有用户关联该角色，请先改授其它角色");
        }
        roleMapper.deleteById(id);
        // 授权一并失效（保留行没有意义，且会让重新创建同 id 时看起来"复活"）
        roleMapper.deleteGrants(id);
        assertAdminSurvives(adminsBefore);
        auditService.record("ROLE", id, "DELETE", "roleCode", entity.getRoleCode(), null);
    }

    // ------------------------------------------------------------------ 授权

    @Transactional
    public RoleDTO replaceGrants(Long id, RoleGrantReq req) {
        CurrentUser me = requireUser();
        RoleRow entity = requireRole(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        if (req == null || req.permissions() == null) {
            throw new BizException(400, "permissions 必填，传空数组即清空");
        }
        List<String> permissions = validatePermissionCodes(req.permissions());
        // 核心防提权：只能授出自己持有的权限点
        ScopeGuard.assertPermissionsWithinOperator(permissions, me);

        List<String> before = roleMapper.findPermissions(id);
        int adminsBefore = userMapper.countActiveAdmins();
        replaceGrants(id, permissions);
        assertAdminSurvives(adminsBefore);

        // 审计必须记差集：全量集合回答不了"谁在何时把 export 给了谁"
        String removed = before.stream().filter(p -> !permissions.contains(p)).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String added = permissions.stream().filter(p -> !before.contains(p)).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        auditService.record("ROLE", id, "GRANT_REPLACE", "permissions", removed, added);
        return toDto(entity, permissions);
    }

    // ------------------------------------------------------------------ 内部

    private void replaceGrants(Long roleId, List<String> permissions) {
        roleMapper.deleteGrants(roleId);
        for (String code : permissions) {
            roleMapper.insertGrant(roleId, code);
        }
    }

    private List<String> validatePermissionCodes(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(permissions));
        for (String code : distinct) {
            if (!PermissionCatalog.contains(code)) {
                throw new BizException(400, "未知的权限点：" + code);
            }
        }
        return distinct;
    }

    private String requireScope(String scope) {
        if (scope == null || !VALID_SCOPES.contains(scope)) {
            throw new BizException(400, "数据范围不合法：" + scope);
        }
        return scope;
    }

    private RoleRow requireRole(Long id) {
        RoleRow entity = roleMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "角色不存在");
        }
        return entity;
    }

    private Map<Long, List<String>> permissionsByRole(List<RoleRow> roles) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (roles.isEmpty()) {
            return result;
        }
        List<Long> ids = roles.stream().map(RoleRow::getId).toList();
        for (RolePermRow row : roleMapper.findPermissionsByRoleIds(ids)) {
            result.computeIfAbsent(row.getRoleId(), k -> new ArrayList<>()).add(row.getPermCode());
        }
        return result;
    }

    /** 任何权限变更后都要保证系统里还剩至少一个可用管理员（否则只能改库恢复）。 */
    private void assertAdminSurvives(int adminsBefore) {
        if (adminsBefore > 0 && userMapper.countActiveAdmins() == 0) {
            throw new BizException(409, "系统必须保留至少一个启用且持有 sys:role:edit 的用户");
        }
    }

    private static RoleDTO toDto(RoleRow r, List<String> permissions) {
        return new RoleDTO(r.getId(), r.getCompanyId(), r.getRoleCode(), r.getRoleName(),
                r.getDataScope(), r.getRemark(), r.getStatus(), List.copyOf(permissions));
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, message);
        }
    }
}
