package com.insbean.payroll.employee.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.EmpCompProfileEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface CompProfileMapper extends BaseMapper<EmpCompProfileEntity> {

    /**
     * 取指定日期生效的档案。区间语义：effective_from 含、effective_to 含、NULL 表示至今。
     * 不做 setScale / 排序以外的任何加工，重叠加了应用层校验，这里只信任最近的版本。
     */
    @Select("""
            SELECT * FROM emp_comp_profile
             WHERE employee_id = #{employeeId} AND deleted = 0 AND status = 1
               AND effective_from <= #{date}
               AND (effective_to IS NULL OR effective_to >= #{date})
             ORDER BY effective_from DESC
             LIMIT 1
            """)
    EmpCompProfileEntity effectiveAt(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);
}
