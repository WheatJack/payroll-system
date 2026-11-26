package com.insbean.payroll.support;

import com.insbean.payroll.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

/**
 * 连真实 MySQL（127.0.0.1:3307/payroll_test）的测试基类。
 * 不 mock 数据库：跨主体唯一索引、生成列、乐观锁这些行为只有真库才验证得了。
 *
 * <p>每个用例默认注入一个 data_scope=ALL 的"集团 HR"身份：
 * Service 层的 {@code DataScopeEnforcer} 会读 CurrentUser，没有身份会直接 401。
 * 需要验证越权的用例在各自的 {@code @BeforeEach} 里覆盖成 SELF/DEPT/DEPT_SUB 即可。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractDbTest {

    protected static final CurrentUser ALL_SCOPE_USER = new CurrentUser(
            1L, TestDb.COMPANY_DEFAULT,
            Set.of("payroll:record:view", "payroll:record:edit",
                    "payroll:period:lock", "payroll:period:unlock"),
            "ALL", null);

    @Autowired
    protected TestDb db;

    @BeforeEach
    void resetDatabase() {
        db.reset();
        CurrentUser.set(ALL_SCOPE_USER);
    }

    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
    }
}
