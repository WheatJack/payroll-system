package com.insbean.payroll.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    /** 集团级查重：同一年月该员工是否有有效记录（可能属于任何主体）。 */
    @Select("""
            SELECT company_id FROM pay_record
             WHERE employee_id = #{employeeId} AND period_ym = #{periodYm} AND deleted = 0
             LIMIT 1
            """)
    Long findCompanyIdByEmployeeYm(@Param("employeeId") Long employeeId, @Param("periodYm") String periodYm);
}
