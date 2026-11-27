package com.insbean.payroll.run;

import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.run.mapper.PayRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动对账：清理进程被杀留下的 RUNNING 批次与过期租约。
 *
 * <p>没有这一步时，半成品期间会同时出现"列表显示 0 人、编制台有 492 行"，
 * 即使重新生成也要先等 10 分钟租约过期——只能靠人工改库恢复。
 */
@Slf4j
@Component
public class RunRecoveryRunner implements ApplicationRunner {

    static final String INTERRUPTED = "进程异常终止，批次未完成";

    private final PayRunMapper runMapper;
    private final PeriodMapper periodMapper;

    public RunRecoveryRunner(PayRunMapper runMapper, PeriodMapper periodMapper) {
        this.runMapper = runMapper;
        this.periodMapper = periodMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /** 幂等：可在启动时跑，也可在测试里直接调用。 */
    public int recover() {
        int runs = runMapper.failAllRunning(INTERRUPTED);
        int leases = periodMapper.releaseExpiredLeases();
        if (runs > 0 || leases > 0) {
            log.warn("启动对账：{} 个 RUNNING 批次标记 FAILED，清理 {} 个过期租约", runs, leases);
        }
        return runs;
    }
}
