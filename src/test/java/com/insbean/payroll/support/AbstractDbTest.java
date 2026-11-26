package com.insbean.payroll.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 连真实 MySQL（127.0.0.1:3307/payroll_test）的测试基类。
 * 不 mock 数据库：跨主体唯一索引、生成列、乐观锁这些行为只有真库才验证得了。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractDbTest {

    @Autowired
    protected TestDb db;

    @BeforeEach
    void resetDatabase() {
        db.reset();
    }
}
