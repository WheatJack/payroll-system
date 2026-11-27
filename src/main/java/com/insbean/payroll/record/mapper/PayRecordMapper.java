package com.insbean.payroll.record.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface PayRecordMapper extends BaseMapper<PayRecordEntity> {

    /** 本主体本期间的有效记录数（不含软删除）。 */
    @Select("""
            SELECT COUNT(*) FROM pay_record
             WHERE period_id = #{periodId} AND company_id = #{companyId} AND deleted = 0
            """)
    int countActive(@Param("periodId") Long periodId, @Param("companyId") Long companyId);

    /**
     * 取本主体本期间已有记录并加行锁。
     * 条件必须同时带 period_id 与 company_id：只按 period_id 取会把别家主体的记录也锁进来，
     * 只按 employee_id 取更会把跨主体的记录误判成"本主体已有"。
     */
    @Select("""
            SELECT * FROM pay_record
             WHERE period_id = #{periodId} AND company_id = #{companyId} AND deleted = 0
             FOR UPDATE
            """)
    List<PayRecordEntity> lockByPeriod(@Param("periodId") Long periodId, @Param("companyId") Long companyId);

    /**
     * 按期间聚合可见记录（人数去重 / 应发 / 实发）。
     *
     * <p>期间表上的 total_* 是**全公司**口径；DEPT_SUB / DEPT / SELF 账号直接读会看到
     * 别人的工资合计。列表与详情都改用它按数据范围重算。
     */
    @Select("""
            SELECT period_id,
                   COUNT(DISTINCT employee_id) AS employee_count,
                   SUM(gross_amount)           AS total_gross,
                   SUM(net_amount)             AS total_net
              FROM pay_record ${ew.customSqlSegment}
            """)
    List<Map<String, Object>> aggregateByPeriod(@Param(Constants.WRAPPER) Wrapper<PayRecordEntity> wrapper);

    /**
     * 员工最近 12 个已锁定期间的平均应发：社保基数默认值。
     * 无历史（新入职）返回 NULL，由调用方回退当月应发。
     */
    @Select("""
            SELECT AVG(gross_amount) FROM (
              SELECT r.gross_amount
                FROM pay_record r
                JOIN pay_period p ON p.id = r.period_id
               WHERE r.employee_id = #{employeeId}
                 AND r.deleted = 0
                 AND r.period_ym < #{periodYm}
                 AND p.status IN ('LOCKED', 'ARCHIVED')
               ORDER BY r.period_ym DESC
               LIMIT 12
            ) recent
            """)
    java.math.BigDecimal averageRecentGross(@Param("employeeId") Long employeeId,
                                            @Param("periodYm") String periodYm);

    /** 集团级查重：同一年月该员工是否有有效记录（可能属于任何主体）。 */
    @Select("""
            SELECT company_id FROM pay_record
             WHERE employee_id = #{employeeId} AND period_ym = #{periodYm} AND deleted = 0
             LIMIT 1
            """)
    Long findCompanyIdByEmployeeYm(@Param("employeeId") Long employeeId, @Param("periodYm") String periodYm);
}
