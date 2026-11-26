package com.insbean.payroll.employee.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.EmpEmploymentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface EmploymentMapper extends BaseMapper<EmpEmploymentEntity> {

    /** 调动时改写当前开放的任职区间（end_date IS NULL）。 */
    @Update("""
            UPDATE emp_employment
               SET company_id = #{companyId}, dept_id = #{deptId}
             WHERE employee_id = #{employeeId} AND end_date IS NULL AND deleted = 0
            """)
    int updateOpenInterval(@Param("employeeId") Long employeeId,
                           @Param("companyId") Long companyId,
                           @Param("deptId") Long deptId);
}
