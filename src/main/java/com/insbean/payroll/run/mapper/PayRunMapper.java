package com.insbean.payroll.run.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.PayRunEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PayRunMapper extends BaseMapper<PayRunEntity> {

    /** 启动对账/重新生成前：把遗留的 RUNNING 批次标记为 FAILED（进程被杀时没人收尾）。 */
    @Update("""
            UPDATE pay_run
               SET status = 'FAILED', error_msg = #{message}, finished_at = NOW()
             WHERE status = 'RUNNING'
            """)
    int failAllRunning(@Param("message") String message);

    @Update("""
            UPDATE pay_run
               SET status = 'FAILED', error_msg = #{message}, finished_at = NOW()
             WHERE period_id = #{periodId} AND status = 'RUNNING'
            """)
    int failRunningByPeriod(@Param("periodId") Long periodId, @Param("message") String message);
}
