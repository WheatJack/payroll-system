package com.insbean.payroll.period;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.period.dto.PeriodDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodServiceTest extends AbstractDbTest {

    private static final long COMPILER = 1L;
    private static final long REVIEWER = 2L;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long periodId;

    @BeforeEach
    void setUpPeriod() {
        periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        // 模拟"已生成"：期间推进到 EDITING，编制人 = COMPILER
        jdbc.update("UPDATE pay_period SET status = 'EDITING', compiled_by = ? WHERE id = ?", COMPILER, periodId);
    }

    @Test
    void 部门级账号不能创建期间() {
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:record:edit"), "DEPT_SUB", null));

        assertThatThrownBy(() -> periodService.create(
                new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void 并发锁定恰好一个成功() throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var first = pool.submit(() -> lockAs(barrier, 2L));
            var second = pool.submit(() -> lockAs(barrier, 3L));

            List<Integer> results = List.of(
                    first.get(60, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(60, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(0, 409);
            // 只有成功的那次写审计，第二次在行锁后看到已锁定直接 409
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pay_audit_log
                     WHERE biz_type = 'PERIOD' AND action = 'LOCK' AND biz_id = ?
                    """, Integer.class, periodId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Integer lockAs(java.util.concurrent.CyclicBarrier barrier, long userId) throws Exception {
        CurrentUser.set(new CurrentUser(userId, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:record:view", "payroll:period:lock"), "ALL", null));
        try {
            barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
            periodService.lock(periodId, userId);
            return 0;
        } catch (BizException e) {
            return e.getCode();
        } finally {
            CurrentUser.clear();
        }
    }

    @Test
    void 已锁定期间拒绝写操作() {
        periodService.lock(periodId, REVIEWER);

        assertThatThrownBy(() -> periodService.assertWritable(periodId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已锁定");
    }

    @Test
    void 租约持有中禁止锁定() {
        periodService.tryAcquire(periodId, "gen-task", Duration.ofMinutes(10));

        assertThatThrownBy(() -> periodService.lock(periodId, REVIEWER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("正在生成")
                .extracting("code").isEqualTo(409);
        // 期间仍是编制中，不能被锁成"半成品已锁定"
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pay_period WHERE id = ?", String.class, periodId))
                .isEqualTo("EDITING");
    }

    @Test
    void 租约过期后可以锁定() {
        jdbc.update("""
                UPDATE pay_period
                   SET processing_owner = 'stale-task',
                       processing_expire_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)
                 WHERE id = ?
                """, periodId);

        assertThat(periodService.lock(periodId, REVIEWER).status()).isEqualTo("LOCKED");
    }

    @Test
    void 租约被占用时第二个任务无法获取() {
        assertThat(periodService.tryAcquire(periodId, "task-1", Duration.ofMinutes(10))).isTrue();
        assertThat(periodService.tryAcquire(periodId, "task-2", Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void 租约过期后可被抢占() throws InterruptedException {
        // 租约 TTL 与过期判定都用数据库时钟；真实等待一小段时间后应可被抢占
        periodService.tryAcquire(periodId, "task-1", Duration.ofMillis(1));
        Thread.sleep(20);

        assertThat(periodService.tryAcquire(periodId, "task-2", Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void 释放租约后他人可立即获取() {
        periodService.tryAcquire(periodId, "task-1", Duration.ofMinutes(10));
        periodService.release(periodId, "task-1");

        assertThat(periodService.tryAcquire(periodId, "task-2", Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void 编制人不能锁定自己编制的期间() {
        assertThatThrownBy(() -> periodService.lock(periodId, COMPILER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编制人");

        assertThat(periodService.get(periodId).status()).isEqualTo("EDITING");
    }

    @Test
    void 草稿期间不能直接锁定必须先生成() {
        Long draft = periodService.create(new PeriodCreateReq(null, "2026-12"), TestDb.COMPANY_DEFAULT).id();

        assertThatThrownBy(() -> periodService.lock(draft, REVIEWER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编制中");
    }

    @Test
    void 归档期间拒绝写入且不能解锁() {
        jdbc.update("UPDATE pay_period SET status = 'ARCHIVED' WHERE id = ?", periodId);

        assertThatThrownBy(() -> periodService.assertWritable(periodId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归档");
        assertThatThrownBy(() -> periodService.unlock(periodId, REVIEWER, "改错了"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 解锁必须填写原因且回到编辑中() {
        periodService.lock(periodId, REVIEWER);

        assertThatThrownBy(() -> periodService.unlock(periodId, REVIEWER, "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");

        PeriodDTO unlocked = periodService.unlock(periodId, REVIEWER, "算错了加班费");
        assertThat(unlocked.status()).isEqualTo("EDITING");
        periodService.assertWritable(periodId);
    }

    @Test
    void 新建期间按主体加年月幂等() {
        PeriodDTO again = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT);
        assertThat(again.id()).isEqualTo(periodId);

        // 不同主体同一月份是两个独立的期间
        PeriodDTO beijing = periodService.create(
                new PeriodCreateReq(TestDb.COMPANY_BEIJING, "2026-11"), TestDb.COMPANY_DEFAULT);
        assertThat(beijing.id()).isNotEqualTo(periodId);
        assertThat(beijing.companyId()).isEqualTo(TestDb.COMPANY_BEIJING);
    }

    @Test
    void 期间格式错误被拒绝() {
        assertThatThrownBy(() -> periodService.create(new PeriodCreateReq(null, "2026-13"), TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式");
    }

    @Test
    void 期间起止日期按自然月计算() {
        assertThat(PeriodService.periodStart("2026-11")).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(PeriodService.periodEnd("2026-11")).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(PeriodService.periodEnd("2028-02")).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void 金额与计薪天数按契约格式下发() {
        PeriodDTO dto = periodService.get(periodId);

        assertThat(dto.totalGross().toPlainString()).isEqualTo("0.00");
        assertThat(dto.totalNet().toPlainString()).isEqualTo("0.00");
        assertThat(dto.hpd().toPlainString()).isEqualTo("21.75");
        assertThat(dto.employeeCount()).isZero();
    }
}
