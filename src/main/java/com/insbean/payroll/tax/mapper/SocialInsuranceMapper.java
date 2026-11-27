package com.insbean.payroll.tax.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.tax.SocialInsuranceEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface SocialInsuranceMapper extends BaseMapper<SocialInsuranceEntity> {

    @Select("""
            SELECT * FROM emp_social_insurance
             WHERE employee_id = #{employeeId}
               AND effective_from <= #{date}
               AND (effective_to IS NULL OR effective_to >= #{date})
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    SocialInsuranceEntity effectiveAt(@Param("employeeId") Long employeeId,
                                      @Param("date") LocalDate date);
}
