package com.insbean.payroll.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insbean.payroll.admin.dto.ResetPasswordReq;
import com.insbean.payroll.admin.dto.UserCreateReq;
import com.insbean.payroll.admin.dto.UserDTO;
import com.insbean.payroll.admin.dto.UserPageDTO;
import com.insbean.payroll.admin.dto.UserUpdateReq;
import com.insbean.payroll.admin.mapper.AdminDeptMapper;
import com.insbean.payroll.admin.mapper.RoleAdminMapper;
import com.insbean.payroll.admin.mapper.UserAdminMapper;
import com.insbean.payroll.admin.mapper.UserPermRow;
import com.insbean.payroll.admin.mapper.UserRoleRow;
import com.insbean.payroll.admin.mapper.UserScopeRow;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import com.insbean.payroll.domain.entity.SysUserEntity;
import com.insbean.payroll.security.AuthService;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理（契约 /users）。
 *
 * <p>安全底线：
 * <ul>
 *   <li>不能停用、删除自己（否则一键把自己锁在系统外）；</li>
 *   <li>授权角色不得超出操作者自身的数据范围；</li>
 *   <li>重置密码复用 {@link AuthService#resetPassword}，token_version 一 bump
 *       旧 token 立即 401——无状态 token 无法单独吊销，这是唯一可靠的做法。</li>
 * </ul>
 */
@Service
public class UserAdminService {

    private static final List<String> SCOPE_ORDER = List.of("ALL", "COMPANY", "DEPT_SUB", "DEPT", "SELF");

    private final UserAdminMapper userMapper;
    private final RoleAdminMapper roleMapper;
    private final AdminDeptMapper deptMapper;
    private final AuthService authService;
    private final AuditService auditService;
    private final DataScopeEnforcer dataScope;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserAdminMapper userMapper,
                            RoleAdminMapper roleMapper,
                            AdminDeptMapper deptMapper,
                            AuthService authService,
                            AuditService auditService,
                            DataScopeEnforcer dataScope,
                            PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.deptMapper = deptMapper;
        this.authService = authService;
        this.auditService = auditService;
        this.dataScope = dataScope;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------ 查询

    public UserPageDTO list(long current, long size, String keyword, Long companyId,
                            Long deptId, Integer status) {
        CurrentUser me = requireUser();
        if (companyId != null) {
            dataScope.assertCompanyVisible(companyId);
        }
        LambdaQueryWrapper<SysUserEntity> query = Wrappers.<SysUserEntity>lambdaQuery()
                .eq(companyId != null, SysUserEntity::getCompanyId, companyId)
                .eq(deptId != null, SysUserEntity::getDeptId, deptId)
                .eq(status != null, SysUserEntity::getStatus, status)
                .orderByAsc(SysUserEntity::getId);
        // 非集团账号只能看本主体账号；即使前端传了别人的 companyId 也已被上面挡掉
        if (!DataScopeEnforcer.ALL.equals(me.dataScope())) {
            query.eq(SysUserEntity::getCompanyId, me.companyId());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            query.and(w -> w.like(SysUserEntity::getUsername, like)
                    .or().like(SysUserEntity::getRealName, like));
        }

        Page<SysUserEntity> page = userMapper.selectPage(new Page<>(current, size), query);
        List<UserDTO> records = toDtos(page.getRecords());
        return new UserPageDTO(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    // ------------------------------------------------------------------ 新增 / 修改 / 删除

    @Transactional
    public UserDTO create(UserCreateReq req) {
        CurrentUser me = requireUser();
        Long companyId = req.companyId() != null ? req.companyId() : me.companyId();
        dataScope.assertCompanyVisible(companyId);

        requireText(req.username(), "用户名不能为空");
        requireText(req.realName(), "姓名不能为空");
        requireText(req.password(), "密码不能为空");
        if (req.password().length() < 8) {
            throw new BizException(400, "密码长度不能少于 8 位");
        }
        if (userMapper.countByUsername(req.username().trim()) > 0) {
            throw new BizException(409, "用户名已存在：" + req.username());
        }
        if (req.employeeId() != null && userMapper.countByEmployeeId(req.employeeId()) > 0) {
            throw new BizException(409, "该人员已关联其它账号");
        }
        validateDept(companyId, req.deptId());
        List<RoleRow> roles = resolveRoles(req.roleIds(), me, companyId, null);

        SysUserEntity entity = new SysUserEntity();
        entity.setCompanyId(companyId);
        entity.setUsername(req.username().trim());
        entity.setPasswordHash(passwordEncoder.encode(req.password()));
        entity.setRealName(req.realName().trim());
        entity.setEmployeeId(req.employeeId());
        entity.setDeptId(req.deptId());
        entity.setStatus(req.status() == null ? 1 : req.status());
        entity.setTokenVersion(0);
        entity.setDeleted(0);
        userMapper.insert(entity);
        replaceRoles(entity.getId(), roles);

        auditService.record("USER", entity.getId(), "CREATE", "username", null, entity.getUsername());
        return toDto(entity, roles);
    }

    @Transactional
    public UserDTO update(Long id, UserUpdateReq req) {
        CurrentUser me = requireUser();
        // 先锁后数：并发互相剥夺权限时，第二个事务必须等第一个提交后再读
        userMapper.lockActiveUsers();
        int adminsBefore = userMapper.countActiveAdmins();
        SysUserEntity entity = requireUser(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        // 低权管理员不能降级/停用/改角色更高权的账号。
        // 例外：给"刚创建、还没有角色"的账号分配角色是开通薪酬岗的唯一入口（F-2），
        // 但只增加了角色、且不是在停用时才放行。
        boolean grantingRoles = req.roleIds() != null && !req.roleIds().isEmpty();
        boolean disabling = req.status() != null && req.status() == 0;
        assertCanManageAccount(entity, me, grantingRoles && !disabling);

        if (req.status() != null && req.status() == 0 && id.equals(me.userId())) {
            throw new BizException(409, "不能停用自己的账号");
        }
        if (req.realName() != null) {
            requireText(req.realName(), "姓名不能为空");
            entity.setRealName(req.realName().trim());
        }
        if (req.employeeId() != null) {
            if (userMapper.countByEmployeeIdExcluding(req.employeeId(), id) > 0) {
                throw new BizException(409, "该人员已关联其它账号");
            }
            entity.setEmployeeId(req.employeeId());
        }
        if (req.deptId() != null) {
            validateDept(entity.getCompanyId(), req.deptId());
            entity.setDeptId(req.deptId());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        userMapper.updateById(entity);

        List<RoleRow> roles = req.roleIds() == null
                ? loadRoles(userMapper.findRoleIds(id))
                : resolveRoles(req.roleIds(), me, entity.getCompanyId(), id);
        if (req.roleIds() != null) {
            // 全量替换：先删后插，事务内完成
            userMapper.deleteUserRoles(id);
            replaceRoles(id, roles);
        }

        assertAdminSurvives(adminsBefore);
        auditService.record("USER", id, "UPDATE", req.roleIds() == null ? null : "roles",
                null, req.roleIds() == null ? null : req.roleIds().toString());
        return toDto(entity, roles);
    }

    @Transactional
    public void delete(Long id) {
        CurrentUser me = requireUser();
        if (id.equals(me.userId())) {
            throw new BizException(409, "不能删除自己的账号");
        }
        SysUserEntity entity = requireUser(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        assertCanManageAccount(entity, CurrentUser.get(), false);
        userMapper.lockActiveUsers();
        int adminsBefore = userMapper.countActiveAdmins();
        // 逻辑删除；拦截器 findById 会立即查不到该用户 → 其 token 下一次请求即 401
        userMapper.deleteById(id);
        assertAdminSurvives(adminsBefore);
        auditService.record("USER", id, "DELETE", "username", entity.getUsername(), null);
    }

    // ------------------------------------------------------------------ 重置密码

    @Transactional
    public UserDTO resetPassword(Long id, ResetPasswordReq req) {
        CurrentUser me = requireUser();
        SysUserEntity entity = requireUser(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        // 低权管理员不能通过"重置密码再登录"接管权限更高的账号
        assertCanManageAccount(entity, me, false);
        String newPassword = req == null ? null : req.newPassword();
        // 契约 minLength=8：服务层也要拦，不能只靠前端表单
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException(400, "新密码不能为空");
        }
        if (newPassword.length() < 8) {
            throw new BizException(400, "密码长度不能少于 8 位");
        }
        // 复用认证域的实现：换 hash + token_version + 1 + 审计
        authService.resetPassword(id, newPassword);
        return toDto(entity, loadRoles(userMapper.findRoleIds(id)));
    }

    // ------------------------------------------------------------------ 内部

    private List<RoleRow> resolveRoles(List<Long> roleIds, CurrentUser me, Long companyId,
                                       Long targetUserId) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(roleIds));
        List<RoleRow> roles = new ArrayList<>();
        for (Long roleId : distinct) {
            RoleRow role = roleMapper.selectById(roleId);
            if (role == null || !role.getCompanyId().equals(companyId)) {
                throw new BizException(400, "角色不存在或不属于该主体：" + roleId);
            }
            dataScope.assertCompanyVisible(role.getCompanyId());
            // 分配的账号范围不能超出操作者自身（避免"给自己配一个 ALL 角色"式提权）
            ScopeGuard.assertScopeNotWider(role.getDataScope(), me);
            // 只比范围不比权限，分公司管理员就能把"权限比自己多"的既有角色挂到自己身上
            List<String> rolePermissions = roleMapper.findPermissions(role.getId());
            ScopeGuard.assertPermissionsWithinOperator(rolePermissions, me);
            // 系统管理员可以给 HR 建号（payroll 权限不要求自己持有），但不能给自己发工资权限
            if (targetUserId != null && targetUserId.equals(me.userId())
                    && rolePermissions.stream().anyMatch(p -> p.startsWith("payroll:"))) {
                throw new BizException(403, "不能给自己授予薪酬权限");
            }
            roles.add(role);
        }
        return roles;
    }

    private List<RoleRow> loadRoles(List<Long> roleIds) {
        List<RoleRow> roles = new ArrayList<>();
        for (Long roleId : roleIds) {
            RoleRow role = roleMapper.selectById(roleId);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    private void replaceRoles(Long userId, List<RoleRow> roles) {
        for (RoleRow role : roles) {
            userMapper.insertUserRole(userId, role.getId());
        }
    }

    private void validateDept(Long companyId, Long deptId) {
        if (deptId == null) {
            return;
        }
        SysDeptEntity dept = deptMapper.selectById(deptId);
        if (dept == null || !dept.getCompanyId().equals(companyId)) {
            throw new BizException(400, "部门不存在或不属于该主体：" + deptId);
        }
    }

    private SysUserEntity requireUser(Long id) {
        SysUserEntity entity = userMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "用户不存在");
        }
        return entity;
    }

    private List<UserDTO> toDtos(List<SysUserEntity> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(SysUserEntity::getId).toList();

        Map<Long, List<Long>> rolesByUser = new LinkedHashMap<>();
        for (UserRoleRow row : userMapper.findRoleLinks(ids)) {
            rolesByUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row.getRoleId());
        }
        Map<Long, Set<String>> permsByUser = new LinkedHashMap<>();
        for (UserPermRow row : userMapper.findPermissions(ids)) {
            permsByUser.computeIfAbsent(row.getUserId(), k -> new LinkedHashSet<>()).add(row.getPermCode());
        }
        Map<Long, List<String>> scopesByUser = new LinkedHashMap<>();
        for (UserScopeRow row : userMapper.findDataScopes(ids)) {
            scopesByUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row.getDataScope());
        }

        return users.stream().map(u -> new UserDTO(
                u.getId(), u.getCompanyId(), u.getUsername(), u.getRealName(),
                u.getEmployeeId(), u.getDeptId(), u.getMobileMask(), u.getStatus(), u.getLastLoginAt(),
                rolesByUser.getOrDefault(u.getId(), List.of()),
                List.copyOf(permsByUser.getOrDefault(u.getId(), Set.of())),
                widestScope(scopesByUser.getOrDefault(u.getId(), List.of())))).toList();
    }

    private UserDTO toDto(SysUserEntity u, List<RoleRow> roles) {
        Set<String> permissions = new LinkedHashSet<>();
        List<String> scopes = new ArrayList<>();
        List<Long> roleIds = new ArrayList<>();
        for (RoleRow role : roles) {
            roleIds.add(role.getId());
            scopes.add(role.getDataScope());
            permissions.addAll(roleMapper.findPermissions(role.getId()));
        }
        return new UserDTO(u.getId(), u.getCompanyId(), u.getUsername(), u.getRealName(),
                u.getEmployeeId(), u.getDeptId(), u.getMobileMask(), u.getStatus(), u.getLastLoginAt(),
                roleIds, List.copyOf(permissions), widestScope(scopes));
    }

    /**
     * 目标账号的范围与权限点必须 ⊆ 操作者自身；否则需要 sys:role:grant:all。
     * 不挡这条路，前面所有防提权不变量都能被"重置密码 → 登录"绕过。
     */
    /**
     * @param allowRolelessTarget 仅"给对方分配角色"的正向操作允许目标暂时没有角色。
     *                            新账号天然无角色，若这里一律 403，"先建号再分配"会被堵死，
     *                            薪酬岗永远开不出来；而重置密码/停用/删除仍必须走无角色即拒绝。
     */
    private void assertCanManageAccount(SysUserEntity target, CurrentUser me,
                                       boolean allowRolelessTarget) {
        List<RoleRow> roles = loadRoles(userMapper.findRoleIds(target.getId()));
        if (roles.isEmpty() && !allowRolelessTarget) {
            // 没有角色的账号处于异常状态：不能按"无权限=可管理"放行，
            // 否则会在"角色刚被清空"的竞态窗口里被低权管理员接管
            throw new BizException(403, "目标账号没有任何角色，禁止通过管理接口操作");
        }
        if (me.has(ScopeGuard.PERM_GRANT_ALL)) {
            return;
        }
        String targetScope = widestScope(roles.stream().map(RoleRow::getDataScope).toList());
        if (ScopeGuard.rank(targetScope) > ScopeGuard.rank(me.dataScope())) {
            throw new BizException(403, "不能管理数据范围高于自己的账号");
        }
        // 只比较 sys:*：薪酬与系统管理是两条线，系统管理员管理 HR 账号时
        // 不应因为对方有 payroll 权限而被挡住；反之 sys 权限高者（含 grant:all）不可被降级。
        Set<String> targetSysPerms = new LinkedHashSet<>();
        for (RoleRow role : roles) {
            roleMapper.findPermissions(role.getId()).stream()
                    .filter(p -> p.startsWith("sys:"))
                    .forEach(targetSysPerms::add);
        }
        if (!me.permissions().containsAll(targetSysPerms)) {
            throw new BizException(403, "不能管理系统管理权限高于自己的账号");
        }
    }

    /** 任何权限变更后都要保证系统里还剩至少一个可用管理员（否则只能改库恢复）。 */
    private void assertAdminSurvives(int adminsBefore) {
        if (adminsBefore > 0 && userMapper.countActiveAdmins() == 0) {
            throw new BizException(409, "系统必须保留至少一个启用且持有 sys:role:edit 的用户");
        }
    }

    static String widestScope(List<String> scopes) {
        for (String candidate : SCOPE_ORDER) {
            if (scopes.contains(candidate)) {
                return candidate;
            }
        }
        return "SELF";
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
