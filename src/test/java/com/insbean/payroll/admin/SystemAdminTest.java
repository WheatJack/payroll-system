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
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.TokenService;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

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
        assertThat(page.records()).extracting(UserDTO::username).containsExactly("hr01", "hr02");
        assertThat(page.total()).isEqualTo(2);

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
                "SELECT COUNT(*) FROM pay_audit_log WHERE biz_type = 'ROLE' AND biz_id = 3 AND action = 'UPDATE'",
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
