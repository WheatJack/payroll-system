package com.insbean.payroll.period.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


public interface PeriodMapper extends BaseMapper<PayPeriodEntity> {

    /**
     * 抢占任务租约。以 affectedRows 判定成败，不加行锁也不先查后改——
     * 两个人同时点"生成"时只有一个人能拿到，另一个立刻失败，不会排队等待。
     *
     * <p><b>过期时间由数据库计算</b>（DATE_ADD(NOW(6), ...)）：写入用 JVM 时间、
     * 判定用数据库时间会在两者时区/时钟不一致时错判——该过期的不过期、没过期的被抢占。
     */
    @Update("""
            UPDATE pay_period
               SET processing_owner = #{owner},
                   processing_expire_at = DATE_ADD(NOW(6), INTERVAL #{ttlMicros} MICROSECOND),
                   version = version + 1
             WHERE id = #{id}
               AND (processing_owner IS NULL
                    OR processing_expire_at IS NULL
                    OR processing_expire_at < NOW(6))
            """)
    int tryAcquire(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("ttlMicros") long ttlMicros);

    /** 租约是否仍然有效（同样以数据库时钟判定，避免 JVM/DB 时区差异）。 */
    @Select("""
            SELECT COUNT(*) FROM pay_period
             WHERE id = #{id}
               AND processing_owner IS NOT NULL
               AND processing_expire_at IS NOT NULL
               AND processing_expire_at > NOW(6)
            """)
    int isLeaseActive(@Param("id") Long id);

    /** 最近一次生成批次的状态；期间从未生成过返回 null。 */
    @Select("SELECT status FROM pay_run WHERE period_id = #{periodId} ORDER BY id DESC LIMIT 1")
    String findLatestRunStatus(@Param("periodId") Long periodId);

    @Update("""
            UPDATE pay_period
               SET processing_owner = NULL,
                   processing_expire_at = NULL,
                   version = version + 1
             WHERE id = #{id} AND processing_owner = #{owner}
            """)
    int release(@Param("id") Long id, @Param("owner") String owner);

    @Select("SELECT company_name FROM sys_company WHERE id = #{companyId} AND deleted = 0")
    String findCompanyName(@Param("companyId") Long companyId);

    /**
     * 写路径读取期间并加行锁。
     * 与普通 SELECT 的差别就是并发锁定场景：
     * 「A 校验状态 → B 锁定期间提交 → A 继续写入」只有在 A 持行锁时才会被 B 挡住。
     */
    @Select("SELECT * FROM pay_period WHERE id = #{id} FOR UPDATE")
    PayPeriodEntity selectForUpdate(@Param("id") Long id);
}
