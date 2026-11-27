package com.insbean.payroll.run;

import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.run.dto.RunProgressDTO;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程被杀后的启动对账：遗留 RUNNING 批次与过期租约必须被清掉，
 * 否则期间列表显示 0 人、编制台却有残留行，且重新生成被租约挡住。
 */
class RunRecoveryTest extends AbstractDbTest {

    @Autowired
    private RunRecoveryRunner runner;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 启动对账清理RUNNING批次与过期租约并可重新生成() {
        long periodId = periodService.create(
                new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        jdbc.update("""
                INSERT INTO pay_run (period_id, run_no, run_type, status, emp_total, emp_success,
                                     emp_failed, operator_id, started_at)
                VALUES (?, 'RUN-CRASH-1', 'INIT', 'RUNNING', 500, 492, 0, 1, NOW())
                """, periodId);
        jdbc.update("""
                UPDATE pay_period
                   SET processing_owner = 'dead-process',
                       processing_expire_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)
                 WHERE id = ?
                """, periodId);

        runner.recover();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM pay_run WHERE run_no = 'RUN-CRASH-1'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_msg FROM pay_run WHERE run_no = 'RUN-CRASH-1'", String.class))
                .contains("进程异常终止");
        assertThat(jdbc.queryForObject(
                "SELECT processing_owner FROM pay_period WHERE id = ?", String.class, periodId))
                .isNull();

        // 租约已清 → 可以重新生成，且新批次正常完成
        Long runId = generateService.startGenerate(periodId, 1L);
        RunProgressDTO progress = generateService.progress(runId);
        assertThat(progress.status()).isEqualTo("SUCCESS");
    }
}
