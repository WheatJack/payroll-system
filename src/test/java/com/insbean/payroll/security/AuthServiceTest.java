package com.insbean.payroll.security;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.TooManyAttemptsException;
import com.insbean.payroll.security.dto.LoginResponse;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest extends AbstractDbTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 连续失败登录触发429并写审计() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login("ghost", "WrongPassw0rd!"))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(401);
        }
        // 第 6 次起先强制等待 2 秒，再处理；仍失败才 429（带 retryAfter）
        assertThatThrownBy(() -> authService.login("ghost", "WrongPassw0rd!"))
                .isInstanceOf(TooManyAttemptsException.class)
                .satisfies(e -> assertThat(((TooManyAttemptsException) e).getRetryAfterSeconds())
                        .isEqualTo(2));

        Integer audits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_audit_log
                 WHERE field_name = 'login_blocked' AND biz_type = 'USER'
                """, Integer.class);
        assertThat(audits).isPositive();
    }

    @Test
    void 五次失败后仍可用正确密码登录且计数清零() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login("hr02", "WrongPassw0rd!"))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(401);
        }

        // 不硬锁：第 6 次正确密码只需等待 2 秒就能登录成功
        LoginResponse response = authService.login("hr02", TestDb.PASSWORD);
        assertThat(response.token()).isNotBlank();

        // 成功后计数清零：下一次错误密码只按普通失败返回 401
        assertThatThrownBy(() -> authService.login("hr02", "WrongPassw0rd!"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(401);
    }

    @Test
    void 登录成功返回token与权限点() {
        LoginResponse response = authService.login("hr01", TestDb.PASSWORD);

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("hr01");
        assertThat(response.user().permissions()).contains("payroll:record:edit");
    }

    @Test
    void 重置密码后旧token立即失效() {
        String oldToken = tokenService.issue(1L);
        assertThat(tokenService.parse(oldToken)).isEqualTo(1L);

        authService.resetPassword(1L, "NewPassw0rd!");

        // tokenVersion +1 → 旧 token 立刻 401，不必等到过期
        assertThatThrownBy(() -> tokenService.parse(oldToken))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未认证");

        // 新密码可登录，新签发的 token 正常可用
        String newToken = authService.login("hr01", "NewPassw0rd!").token();
        assertThat(tokenService.parse(newToken)).isEqualTo(1L);
    }

    @Test
    void 重置密码后旧密码不能再登录() {
        authService.resetPassword(1L, "NewPassw0rd!");

        assertThatThrownBy(() -> authService.login("hr01", TestDb.PASSWORD))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void 密码为空被拒绝() {
        assertThatThrownBy(() -> authService.resetPassword(1L, "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("新密码不能为空");
    }
}
