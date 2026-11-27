package com.insbean.payroll.tax.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.tax.SpecialDeductionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface SpecialDeductionMapper extends BaseMapper<SpecialDeductionEntity> {

    /** 指定日期生效的专项附加扣除月度合计。 */
    @Select("""
            SELECT COALESCE(SUM(monthly_amount), 0) FROM emp_special_deduction
             WHERE employee_id = #{employeeId}
               AND effective_from <= #{date}
               AND (effective_to IS NULL OR effective_to >= #{date})
            """)
    BigDecimal sumEffectiveAt(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    @Select("""
            SELECT * FROM emp_special_deduction
             WHERE employee_id = #{employeeId}
             ORDER BY effective_from DESC, id
            """)
    java.util.List<SpecialDeductionEntity> listByEmployee(@Param("employeeId") Long employeeId);
}
