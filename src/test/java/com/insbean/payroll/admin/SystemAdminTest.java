package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.CompanyCreateReq;
import com.insbean.payroll.admin.dto.DeptCreateReq;
import com.insbean.payroll.admin.dto.DeptUpdateReq;
import com.insbean.payroll.admin.dto.PermissionItemDTO;
import com.insbean.payroll.admin.dto.ResetPasswordReq;
import com.insbean.payroll.admin.dto.RoleCreateReq;
import com.insbean.payroll.admin.dto.RoleDTO;
import com.insbean.payroll.admin.dto.RoleGrantReq;
import com.insbean.payroll.admin.dto.RoleUpdateReq;
import com.insbean.payroll.admin.dto.UserCreateReq;
import com.insbean.payroll.admin.dto.UserDTO;
import com.insbean.payroll.admin.dto.UserPageDTO;
import com.insbean.payroll.admin.dto.UserUpdateReq;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.org.DeptService;
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.TokenService;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统管理（Task 22）验收：用户/角色/授权/部门/主体。
 *
 * <p>重点不是 CRUD 能不能跑，而是评审反复强调的三条安全约束：
 * 防自我提权、不能锁死自己、重置密码后旧 token 立即失效。
 */
class SystemAdminTest extends AbstractDbTest {

    private static final Set<String> FULL_ADMIN_PERMS = Set.of(
            "payroll:record:view", "payroll:record:edit", "payroll:period:lock",
            "payroll:period:unlock", "payroll:import:submit", "payroll:export",
            "payroll:sensitive:view", "sys:user:view", "sys:user:edit",
            "sys:role:view", "sys:role:edit", "sys:role:grant:all",
            "sys:dept:edit", "sys:company:edit");

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private RoleAdminService roleAdminService;

    @Autowired
    private DeptAdminService deptAdminService;

    @Autowired
    private CompanyAdminService companyAdminService;

    @Autowired
    private DeptService deptService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JdbcTemplate jdbc;

    private void loginAs(long userId, long companyId, String scope, Set<String> perms) {
        CurrentUser.set(new CurrentUser(userId, companyId, perms, scope, null));
    }

    // ------------------------------------------------------------------ 防提权

    @Test
    void 管理员给自己改数据范围为ALL被拒绝() {
        // 先以集团管理员身份建一个 COMPANY 范围的角色
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        RoleDTO role = roleAdminService.create(new RoleCreateReq(
                TestDb.COMPANY_DEFAULT, "HR_LEAD", "人事主管", "COMPANY", null, 1, List.of()));

        // 换成只有 COMPANY 范围的运营管理员，它把该角色改成 ALL 就是给自己提权
        loginAs(1L, TestDb.COMPANY_DEFAULT, "COMPANY",
                Set.of("sys:role:view", "sys:role:edit", "sys:user:view", "sys:user:edit"));

        assertThatThrownBy(() -> roleAdminService.update(role.id(),
                new RoleUpdateReq(null, null, "ALL", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("数据范围");
    }

    @Test
    void 授予未持有的权限点被拒绝() {
        // 操作者没有 payroll:export
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL",
                Set.of("sys:role:view", "sys:role:edit", "payroll:record:view"));

        assertThatThrownBy(() -> roleAdminService.replaceGrants(3L,
                new RoleGrantReq(List.of("payroll:record:view", "payroll:export"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未持有");

        // 越权失败必须整体回滚，原授权不受影响
        assertThat(roleAdminService.list(TestDb.COMPANY_DEFAULT, null).stream()
                .filter(r -> r.id() == 3L).findFirst().orElseThrow().permissions())
                .containsExactly("payroll:record:view");
    }

    @Test
    void 改为ALL但缺少grantALL权限点被拒绝() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", Set.of("sys:role:view", "sys:role:edit"));
        assertThatThrownBy(() -> roleAdminService.update(3L,
                new RoleUpdateReq(null, null, "ALL", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sys:role:grant:all");
    }

    // ------------------------------------------------------------------ 不能锁死自己

    @Test
    void 不能停用自己() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> userAdminService.update(1L,
                new UserUpdateReq(null, null, null, 0, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void 不能删除自己() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> userAdminService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("删除");
        // 账号仍在
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = 1 AND deleted = 0", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 重置密码

    @Test
    void 重置密码后旧token立即失效() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        String oldToken = tokenService.issue(2L);
        assertThat(tokenService.parse(oldToken)).isEqualTo(2L);

        userAdminService.resetPassword(2L, new ResetPasswordReq("NewPassw0rd!"));

        assertThatThrownBy(() -> tokenService.parse(oldToken))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未认证");
    }

    @Test
    void 重置密码少于8位被拒绝() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> userAdminService.resetPassword(2L, new ResetPasswordReq("short")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("8");
    }

    // ------------------------------------------------------------------ 用户

    @Test
    void 新增用户_用户名重复返回409且密码不回显() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> userAdminService.create(new UserCreateReq(
                TestDb.COMPANY_DEFAULT, "hr01", "Passw0rd!", "重复用户", null, null, 1, List.of())))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);

        UserDTO created = userAdminService.create(new UserCreateReq(
                TestDb.COMPANY_DEFAULT, "clerk03", "Passw0rd!", "新同事", null, 1L, 1, List.of(1L)));
        assertThat(created.username()).isEqualTo("clerk03");
        assertThat(created.roleIds()).containsExactly(1L);
        assertThat(created.permissions()).contains("payroll:record:edit");
        assertThat(created.dataScope()).isEqualTo("ALL");
    }

    @Test
    void 用户列表按主体过滤且分页() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "COMPANY",
                Set.of("sys:user:view", "sys:user:edit"));
        UserPageDTO page = userAdminService.list(1, 10, null, null, null, null);
        assertThat(page.records()).extracting(UserDTO::username).containsExactly("hr01", "hr02", "admin");
        assertThat(page.total()).isEqualTo(3);

        // 跨主体新建用户被拒
        assertThatThrownBy(() -> userAdminService.create(new UserCreateReq(
                TestDb.COMPANY_BEIJING, "bj01", "Passw0rd!", "北京同事", null, null, 1, List.of())))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void 同一人员不能关联两个账号() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        userAdminService.create(new UserCreateReq(
                TestDb.COMPANY_DEFAULT, "u_emp1", "Passw0rd!", "甲", 9001L, null, 1, List.of()));
        assertThatThrownBy(() -> userAdminService.create(new UserCreateReq(
                TestDb.COMPANY_DEFAULT, "u_emp2", "Passw0rd!", "乙", 9001L, null, 1, List.of())))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    // ------------------------------------------------------------------ 角色 / 授权

    @Test
    void 角色授权是全量替换并写审计() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);

        RoleDTO updated = roleAdminService.replaceGrants(3L,
                new RoleGrantReq(List.of("payroll:record:view", "sys:user:view")));
        assertThat(updated.permissions()).containsExactlyInAnyOrder(
                "payroll:record:view", "sys:user:view");

        // 再传空数组即清空
        roleAdminService.replaceGrants(3L, new RoleGrantReq(List.of()));
        assertThat(roleAdminService.list(null, null).stream()
                .filter(r -> r.id() == 3L).findFirst().orElseThrow().permissions()).isEmpty();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_audit_log WHERE biz_type = 'ROLE' AND biz_id = 3 AND action = 'GRANT_REPLACE'",
                Integer.class)).isPositive();
    }

    @Test
    void 角色编码同主体内唯一_删除有用户关联的角色409() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> roleAdminService.create(new RoleCreateReq(
                TestDb.COMPANY_DEFAULT, "PAYROLL_CLERK", "重复", "SELF", null, 1, List.of())))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);

        // 角色 1 已授予 hr01
        assertThatThrownBy(() -> roleAdminService.delete(1L))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);

        // 角色 3 没有用户关联，可删
        roleAdminService.delete(3L);
        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM sys_role WHERE id = 3", Integer.class)).isEqualTo(1);
    }

    @Test
    void 分配角色也要校验权限点子集() {
        // 操作者只有系统管理权限，没有 payroll:record:edit
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL",
                Set.of("sys:user:view", "sys:user:edit", "sys:role:view", "sys:role:edit"));

        assertThatThrownBy(() -> userAdminService.create(new UserCreateReq(
                TestDb.COMPANY_DEFAULT, "newclerk", "Passw0rd!", "新同事", null, null, 1, List.of(1L))))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'newclerk'", Integer.class)).isZero();
    }

    @Test
    void 不能重置权限高于自己的账号密码() {
        // 用户 2 的角色是 ALL 范围且持有 payroll:export 等权限
        loginAs(1L, TestDb.COMPANY_DEFAULT, "COMPANY",
                Set.of("sys:user:view", "sys:user:edit"));
        assertThatThrownBy(() -> userAdminService.resetPassword(2L, new ResetPasswordReq("NewPassw0rd!")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);

        // 持有 sys:role:grant:all 时可以跨级管理
        loginAs(1L, TestDb.COMPANY_DEFAULT, "COMPANY",
                Set.of("sys:user:view", "sys:user:edit", "sys:role:grant:all"));
        assertThat(userAdminService.resetPassword(2L, new ResetPasswordReq("NewPassw0rd!")).id())
                .isEqualTo(2L);
    }

    @Test
    void 不能移除最后一个系统管理员的权限() {
        // 先摘掉种子管理员，构造"只剩 hr01 一个管理员"的场景
        jdbc.update("DELETE FROM sys_role_grant WHERE role_id = 4 AND perm_code = 'sys:role:edit'");
        // 让角色 1 成为唯一持有 sys:role:edit 的角色，hr01 是唯一使用者
        jdbc.update("INSERT INTO sys_role_grant (role_id, perm_code) VALUES (1, 'sys:role:edit')");
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);

        assertThatThrownBy(() -> roleAdminService.replaceGrants(1L, new RoleGrantReq(List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sys:role:edit");
        // 回滚后授权仍在
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_grant WHERE role_id = 1 AND perm_code = 'sys:role:edit'",
                Integer.class)).isEqualTo(1);

        // 移除用户 1 的角色同样会让自己失去最后的系统管理能力
        assertThatThrownBy(() -> userAdminService.update(1L,
                new UserUpdateReq(null, null, null, null, List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sys:role:edit");
    }

    @Test
    void 不能停用最后一个系统管理员() {
        // 先摘掉种子管理员；只给角色 2（hr02）授予 sys:role:edit，hr02 成为唯一管理员
        jdbc.update("DELETE FROM sys_role_grant WHERE role_id = 4 AND perm_code = 'sys:role:edit'");
        jdbc.update("INSERT INTO sys_role_grant (role_id, perm_code) VALUES (2, 'sys:role:edit')");
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);

        assertThatThrownBy(() -> userAdminService.update(2L,
                new UserUpdateReq(null, null, null, 0, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sys:role:edit");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id = 2", Integer.class)).isEqualTo(1);
    }

    @Test
    void 授权审计记录差集而不是全量() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);

        roleAdminService.replaceGrants(3L,
                new RoleGrantReq(List.of("payroll:record:view", "sys:user:view")));
        Map<String, Object> added = jdbc.queryForMap("""
                SELECT old_value, new_value FROM pay_audit_log
                 WHERE biz_type = 'ROLE' AND biz_id = 3 AND action = 'GRANT_REPLACE'
                 ORDER BY id DESC LIMIT 1
                """);
        assertThat(added.get("old_value")).isEqualTo("");
        assertThat(added.get("new_value")).isEqualTo("sys:user:view");

        roleAdminService.replaceGrants(3L, new RoleGrantReq(List.of("payroll:record:view")));
        Map<String, Object> removed = jdbc.queryForMap("""
                SELECT old_value, new_value FROM pay_audit_log
                 WHERE biz_type = 'ROLE' AND biz_id = 3 AND action = 'GRANT_REPLACE'
                 ORDER BY id DESC LIMIT 1
                """);
        assertThat(removed.get("old_value")).isEqualTo("sys:user:view");
        assertThat(removed.get("new_value")).isEqualTo("");
    }

    @Test
    void 权限子集不足时即使数据范围相同也拒绝重置密码() {
        // 操作者同样是 ALL 范围（rank 不会拦），但权限点少于目标账号
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL",
                Set.of("sys:user:view", "sys:user:edit"));

        assertThatThrownBy(() -> userAdminService.resetPassword(2L, new ResetPasswordReq("NewPassw0rd!")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void 修改用户分配角色也要校验权限子集() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL",
                Set.of("sys:user:view", "sys:user:edit", "sys:role:view", "sys:role:edit"));

        assertThatThrownBy(() -> userAdminService.update(2L,
                new UserUpdateReq(null, null, null, null, List.of(1L))))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        // 回滚：原角色没有被替换
        assertThat(jdbc.queryForObject(
                "SELECT role_id FROM sys_user_role WHERE user_id = 2", Long.class)).isEqualTo(2L);
    }

    @Test
    void 授权差集为空不写审计() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        int before = grantAuditCount(3L);

        // 与当前授权完全相同 → 不产生空审计
        roleAdminService.replaceGrants(3L, new RoleGrantReq(List.of("payroll:record:view")));

        assertThat(grantAuditCount(3L)).isEqualTo(before);
    }

    @Test
    void 并发互相移除管理员权限恰好一个成功() throws Exception {
        // 摘掉种子管理员，构造两个用户各持一个管理员角色的初始状态
        jdbc.update("DELETE FROM sys_role_grant WHERE role_id = 4 AND perm_code = 'sys:role:edit'");
        jdbc.update("""
                INSERT INTO sys_role_grant (role_id, perm_code) VALUES
                  (1, 'sys:role:edit'), (2, 'sys:role:edit')
                """);
        assertThat(activeAdminCount()).isEqualTo(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Integer> a = pool.submit(() -> removeAdminGrant(barrier, 1L, 1L));
                Future<Integer> b = pool.submit(() -> removeAdminGrant(barrier, 2L, 2L));

                List<Integer> results = List.of(
                        a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));

                // 真库、真事务：恰好一个成功，另一个被 409 挡住并回滚
                assertThat(results).containsExactlyInAnyOrder(0, 409);
                assertThat(activeAdminCount()).isPositive();

                // 恢复下一轮初始状态（幂等：先删后插）
                jdbc.update("DELETE FROM sys_role_grant WHERE role_id IN (1, 2) AND perm_code = 'sys:role:edit'");
                jdbc.update("""
                        INSERT INTO sys_role_grant (role_id, perm_code) VALUES
                          (1, 'sys:role:edit'), (2, 'sys:role:edit')
                        """);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 独立线程 + 独立事务：两个管理员同时互相剥夺对方的 sys:role:edit。 */
    private Integer removeAdminGrant(CyclicBarrier barrier, long roleId, long userId) throws Exception {
        CurrentUser.set(new CurrentUser(userId, TestDb.COMPANY_DEFAULT, FULL_ADMIN_PERMS, "ALL", null));
        try {
            barrier.await(30, TimeUnit.SECONDS);
            roleAdminService.replaceGrants(roleId, new RoleGrantReq(List.of()));
            return 0;
        } catch (BizException e) {
            return e.getCode();
        } finally {
            CurrentUser.clear();
        }
    }

    private int activeAdminCount() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT u.id)
                  FROM sys_user u
                  JOIN sys_user_role ur ON ur.user_id = u.id
                  JOIN sys_role r       ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
                  JOIN sys_role_grant g ON g.role_id = r.id AND g.perm_code = 'sys:role:edit'
                 WHERE u.deleted = 0 AND u.status = 1
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private int grantAuditCount(long roleId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_audit_log
                 WHERE biz_type = 'ROLE' AND biz_id = ? AND action = 'GRANT_REPLACE'
                """, Integer.class, roleId);
        return count == null ? 0 : count;
    }

    @Test
    void 权限点目录由后端给出() {
        List<PermissionItemDTO> all = PermissionCatalog.all();
        assertThat(all).hasSize(14);
        assertThat(all).extracting(PermissionItemDTO::code)
                .contains("payroll:record:view", "sys:user:edit", "sys:role:grant:all");
        assertThat(all).allSatisfy(p -> {
            assertThat(p.name()).isNotBlank();
            assertThat(p.module()).isIn("PAYROLL", "SYS");
        });
    }

    // ------------------------------------------------------------------ 部门

    @Test
    void 部门祖先路径由后端生成且父变更级联重算() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);

        DeptDTO root = deptAdminService.create(new DeptCreateReq(
                TestDb.COMPANY_DEFAULT, 0L, "RD", "研发部", 10, 1));
        assertThat(root.ancestors()).isEmpty();

        DeptDTO child = deptAdminService.create(new DeptCreateReq(
                TestDb.COMPANY_DEFAULT, root.id(), "RD1", "研发一组", 10, 1));
        assertThat(child.ancestors()).isEqualTo("," + root.id() + ",");

        DeptDTO grand = deptAdminService.create(new DeptCreateReq(
                TestDb.COMPANY_DEFAULT, child.id(), "RD11", "研发一组小队", 10, 1));
        assertThat(grand.ancestors()).isEqualTo("," + root.id() + "," + child.id() + ",");

        // 把 child 挂到根：child 与 grand 的 ancestors 都要级联重算
        deptAdminService.update(child.id(), new DeptUpdateReq(0L, null, null, null, null));
        assertThat(deptAncestors(child.id())).isEmpty();
        assertThat(deptAncestors(grand.id())).isEqualTo("," + child.id() + ",");
    }

    @Test
    void 部门不能挂到自己的下级且不能删除有下级的部门() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        DeptDTO root = deptAdminService.create(new DeptCreateReq(
                TestDb.COMPANY_DEFAULT, 0L, "OPS", "运营部", 10, 1));
        DeptDTO child = deptAdminService.create(new DeptCreateReq(
                TestDb.COMPANY_DEFAULT, root.id(), "OPS1", "运营一组", 10, 1));

        assertThatThrownBy(() -> deptAdminService.update(root.id(),
                new DeptUpdateReq(child.id(), null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("环");

        assertThatThrownBy(() -> deptAdminService.delete(root.id()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);

        // 部门 1 下仍有用户/员工，也不允许删除
        assertThatThrownBy(() -> deptAdminService.delete(TestDb.DEPT_DEFAULT))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void 部门列表默认不含停用部门_includeDisabled可返回() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        DeptDTO disabled = deptAdminService.create(new DeptCreateReq(
                TestDb.COMPANY_DEFAULT, 0L, "OLD", "历史部门", 50, 1));
        deptAdminService.update(disabled.id(), new DeptUpdateReq(null, null, null, null, 0));

        assertThat(deptService.tree(TestDb.COMPANY_DEFAULT, false))
                .extracting(DeptDTO::id).doesNotContain(disabled.id());
        assertThat(deptService.tree(TestDb.COMPANY_DEFAULT, true))
                .extracting(DeptDTO::id).contains(disabled.id());
        // 兼容旧签名：默认行为不变
        assertThat(deptService.tree(TestDb.COMPANY_DEFAULT))
                .extracting(DeptDTO::id).doesNotContain(disabled.id());
    }

    // ------------------------------------------------------------------ 主体

    @Test
    void 主体编码全局唯一且只有集团账号能新增() {
        loginAs(1L, TestDb.COMPANY_DEFAULT, "COMPANY", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> companyAdminService.create(
                new CompanyCreateReq("SZ", "深圳分公司", 1)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);

        loginAs(1L, TestDb.COMPANY_DEFAULT, "ALL", FULL_ADMIN_PERMS);
        assertThatThrownBy(() -> companyAdminService.create(
                new CompanyCreateReq("DEFAULT", "重复主体", 1)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);

        assertThat(companyAdminService.create(new CompanyCreateReq("SZ", "深圳分公司", 1)).id())
                .isPositive();
    }

    private String deptAncestors(Long deptId) {
        return jdbc.queryForObject("SELECT ancestors FROM sys_dept WHERE id = ?", String.class, deptId);
    }
}
