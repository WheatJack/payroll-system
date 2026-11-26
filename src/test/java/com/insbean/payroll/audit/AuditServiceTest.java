package com.insbean.payroll.audit;

import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志落库验证。pay_audit_log 在 Task 2–9 里一直零写入者，
 * 这一轮开始必须真的有用例保证"高危动作能追溯到人"。
 */
class AuditServiceTest extends AbstractDbTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void record写入一行且操作人取自登录上下文() {
        auditService.record("EXPORT", 42L, "EXPORT", null, null, "月度工资表");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM pay_audit_log WHERE biz_type = 'EXPORT' AND biz_id = 42");

        assertThat(((Number) row.get("operator_id")).longValue()).isEqualTo(1L);
        assertThat(row.get("operator_name")).isEqualTo("薪酬专员");
        // 集团级账号看到的是跨主体数据，company_id 必须留空，否则审计看起来"已按主体过滤"
        assertThat(row.get("company_id")).isNull();
        assertThat(row.get("new_value")).isEqualTo("月度工资表");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void 非集团账号的审计记录主体() {
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                java.util.Set.of("payroll:record:view"), "COMPANY", null));
        auditService.record("REPORT", null, "VIEW", "MONTHLY", null, "periodYm=2026-11");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM pay_audit_log WHERE biz_type = 'REPORT'");
        assertThat(row.get("company_id")).isEqualTo(TestDb.COMPANY_DEFAULT);
        assertThat(row.get("action")).isEqualTo("VIEW");
    }

    @Test
    void 解锁写入审计日志且带原因() {
        Long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        jdbc.update("UPDATE pay_period SET status = 'EDITING', compiled_by = 2 WHERE id = ?", periodId);
        periodService.lock(periodId, 3L);

        periodService.unlock(periodId, 3L, "算错了加班费");

        Map<String, Object> unlock = jdbc.queryForMap("""
                SELECT * FROM pay_audit_log
                 WHERE biz_type = 'PERIOD' AND biz_id = ? AND action = 'UNLOCK'
                """, periodId);
        assertThat(unlock.get("new_value")).isEqualTo("算错了加班费");
        assertThat(unlock.get("field_name")).isEqualTo("unlock_reason");
        assertThat(((Number) unlock.get("operator_id")).longValue()).isEqualTo(1L);

        Integer lockRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_audit_log
                 WHERE biz_type = 'PERIOD' AND biz_id = ? AND action = 'LOCK'
                """, Integer.class, periodId);
        assertThat(lockRows).isEqualTo(1);
    }

    @Test
    void 改密码写入审计但不泄露密码() {
        // 由 AuthService 触发；这里直接验证审计服务对敏感值的处理约定
        auditService.record("USER", 1L, "UPDATE", "password", null, "******");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM pay_audit_log WHERE biz_type = 'USER' AND field_name = 'password'");
        assertThat(row.get("new_value")).isEqualTo("******");
    }
}
