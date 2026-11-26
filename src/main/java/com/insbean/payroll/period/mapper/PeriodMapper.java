package com.insbean.payroll.period.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface PeriodMapper extends BaseMapper<PayPeriodEntity> {

    /**
     * 抢占任务租约。以 affectedRows 判定成败，不加行锁也不先查后改——
     * 两个人同时点"生成"时只有一个人能拿到，另一个立刻失败，不会排队等待。
     *
     * <p>用 NOW(6) 而不是 NOW()：租约 TTL 可能短到毫秒级，秒级精度会把
     * "已过期"判断拖到下一秒，导致快速重试全部失败。
     */
    @Update("""
            UPDATE pay_period
               SET processing_owner = #{owner},
                   processing_expire_at = #{expireAt},
                   version = version + 1
             WHERE id = #{id}
               AND (processing_owner IS NULL
                    OR processing_expire_at IS NULL
                    OR processing_expire_at < NOW(6))
            """)
    int tryAcquire(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("expireAt") LocalDateTime expireAt);

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
