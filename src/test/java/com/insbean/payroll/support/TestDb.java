package com.insbean.payroll.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 测试库基线数据管理。
 *
 * <p>每次 {@link #reset()} 清空全部业务表并重建一套确定性基线：
 * 3 家主体（默认 / 北京 / 上海）、每家 1 个部门、9 个集团通用科目、
 * 4 个内置角色（含 SYS_ADMIN）、3 个测试账号（hr01 编制 / hr02 复核 / admin 系统管理员）。
 *
 * <p>用 TRUNCATE 而不是 DELETE，是为了让自增 ID 从 1 开始，测试断言可预期。
 */
@Component
public class TestDb {

    /** 测试账号统一密码。 */
    public static final String PASSWORD = "Passw0rd!";

    public static final long COMPANY_DEFAULT = 1L;
    public static final long COMPANY_BEIJING = 2L;
    public static final long COMPANY_SHANGHAI = 3L;

    public static final long DEPT_DEFAULT = 1L;
    public static final long DEPT_BEIJING = 2L;
    public static final long DEPT_SHANGHAI = 3L;

    private static final List<String> TABLES = List.of(
            "emp_comp_profile_item", "emp_comp_profile", "emp_employment", "emp_employee",
            "pay_record_item", "pay_record", "pay_run_skip", "pay_run", "pay_period",
            "pay_import_error", "pay_import_batch", "pay_audit_log", "sys_config",
            "pay_item", "sys_user_role", "sys_role_grant", "sys_role", "sys_user",
            "sys_dept", "sys_company");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public TestDb(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public void reset() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seed();
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO sys_company (id, company_code, company_name, status) VALUES
                  (1, 'DEFAULT', '默认主体', 1),
                  (2, 'BJ', '北京分公司', 1),
                  (3, 'SH', '上海分公司', 1)
                """);

        jdbc.update("""
                INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status) VALUES
                  (1, 1, 0, 'D001', '综合部',     '', 10, 1),
                  (2, 2, 0, 'D001', '北京研发部', '', 10, 1),
                  (3, 3, 0, 'D001', '上海研发部', '', 10, 1)
                """);

        jdbc.update("""
                INSERT INTO pay_item
                  (id, company_id, item_code, item_name, category, direction, data_type,
                   precision_scale, include_in_gross, editable, importable, sort_no, status) VALUES
                  (1, 0, 'BASE_SALARY',   '基本工资', 'FIXED',    1, 'AMOUNT', 2, 1, 0, 0,  10, 1),
                  (2, 0, 'POST_ALLOW',    '岗位津贴', 'FIXED',    1, 'AMOUNT', 2, 1, 0, 1,  20, 1),
                  (3, 0, 'SENIORITY_PAY', '工龄工资', 'COMPUTED', 1, 'AMOUNT', 2, 1, 0, 0,  30, 1),
                  (4, 0, 'PERF_BONUS',    '绩效奖金', 'VARIABLE', 1, 'AMOUNT', 2, 1, 1, 1,  40, 1),
                  (5, 0, 'OT_ALLOW',      '加班工资', 'VARIABLE', 1, 'AMOUNT', 2, 1, 1, 1,  50, 1),
                  (6, 0, 'OTHER_BONUS',   '其他奖金', 'VARIABLE', 1, 'AMOUNT', 2, 1, 1, 1,  55, 1),
                  (7, 0, 'ATT_DEDUCT',    '考勤扣款', 'VARIABLE', 2, 'AMOUNT', 2, 1, 1, 1,  70, 1),
                  (8, 0, 'OTHER_DEDUCT',  '其他扣款', 'VARIABLE', 2, 'AMOUNT', 2, 1, 1, 1,  75, 1),
                  (9, 0, 'ADJ_ROUNDING',  '尾差调整', 'COMPUTED', 1, 'AMOUNT', 2, 1, 0, 0, 999, 1)
                """);

        jdbc.update("""
                INSERT INTO sys_role (id, company_id, role_code, role_name, data_scope, status) VALUES
                  (1, 1, 'PAYROLL_CLERK',    '薪酬专员',   'ALL', 1),
                  (2, 1, 'PAYROLL_REVIEWER', '薪酬复核',   'ALL', 1),
                  (3, 1, 'EMPLOYEE',         '普通员工',   'SELF', 1),
                  (4, 1, 'SYS_ADMIN',        '系统管理员', 'ALL', 1)
                """);

        jdbc.update("""
                INSERT INTO sys_role_grant (role_id, perm_code) VALUES
                  (1, 'payroll:record:view'), (1, 'payroll:record:edit'),
                  (1, 'payroll:import:submit'), (1, 'payroll:export'),
                  (2, 'payroll:record:view'), (2, 'payroll:period:lock'), (2, 'payroll:period:unlock'), (2, 'payroll:export'),
                  (3, 'payroll:record:view'),
                  (4, 'sys:user:view'), (4, 'sys:user:edit'),
                  (4, 'sys:role:view'), (4, 'sys:role:edit'), (4, 'sys:role:grant:all'),
                  (4, 'sys:dept:edit'), (4, 'sys:company:edit'),
                  (4, 'sys:config:view'), (4, 'sys:config:edit')
                """);

        String hash = passwordEncoder.encode(PASSWORD);
        jdbc.update("""
                INSERT INTO sys_user (id, company_id, username, password_hash, real_name, dept_id, status) VALUES
                  (1, 1, 'hr01', ?, '薪酬专员', 1, 1),
                  (2, 1, 'hr02', ?, '薪酬复核', 1, 1),
                  (3, 1, 'admin', ?, '系统管理员', 1, 1)
                """, hash, hash, hash);

        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1), (2, 2), (3, 4)");

        jdbc.update("""
                INSERT INTO sys_config (config_key, config_value, value_type, description, editable) VALUES
                  ('payroll.monthly-payable-days', '21.75', 'DECIMAL', '月平均计薪天数', 1),
                  ('payroll.seniority-pay-per-year', '100', 'DECIMAL', '工龄工资每年金额', 1),
                  ('payroll.calendar.holidays', '', 'DATE_LIST', '法定节假日', 1),
                  ('payroll.system-version', '1', 'TEXT', '系统版本号（只读）', 0)
                """);
    }
}
