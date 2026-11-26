package com.insbean.payroll.employee.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.EmpEmploymentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface EmploymentMapper extends BaseMapper<EmpEmploymentEntity> {

    /**
     * 关闭一个任职区间（只写 end_date）。
     *
     * <p>刻意做成"只改一列"的定向更新：任职区间是核算时判定法人主体的历史依据，
     * 任何改写既有行 company_id / dept_id 的操作都等于篡改历史工资的归属。
     * 调动一律「关闭旧区间 + 追加新区间」。
     */
    @Update("""
            UPDATE emp_employment
               SET end_date = #{endDate}
             WHERE id = #{id} AND end_date IS NULL AND deleted = 0
            """)
    int closeInterval(@Param("id") Long id, @Param("endDate") LocalDate endDate);

    /** 某员工在指定日期归属的法人主体；无区间数据返回 null。 */
    @Select("""
            SELECT company_id FROM emp_employment
             WHERE employee_id = #{employeeId} AND deleted = 0
               AND start_date <= #{date}
               AND (end_date IS NULL OR end_date >= #{date})
             ORDER BY start_date DESC, id DESC
             LIMIT 1
            """)
    Long findCompanyAt(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    /**
     * 某员工在指定日期归属的部门。
     *
     * <p>工资表的部门快照必须与 company_id 同口径取「期间末日的任职部门」，
     * 读 emp_employee.dept_id 会把今天的部门写进历史工资表（调动后历史就串了）。
     */
    @Select("""
            SELECT dept_id FROM emp_employment
             WHERE employee_id = #{employeeId} AND deleted = 0
               AND start_date <= #{date}
               AND (end_date IS NULL OR end_date >= #{date})
             ORDER BY start_date DESC, id DESC
             LIMIT 1
            """)
    Long findDeptAt(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);
}
