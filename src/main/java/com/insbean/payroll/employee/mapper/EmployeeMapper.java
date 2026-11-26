package com.insbean.payroll.employee.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface EmployeeMapper extends BaseMapper<EmpEmployeeEntity> {

    @Select("SELECT COUNT(*) FROM emp_employee WHERE id_no_hash = #{hash} AND deleted = 0")
    int countByIdNoHash(@Param("hash") String hash);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE id_no_hash = #{hash} AND deleted = 0 AND id <> #{excludeId}")
    int countByIdNoHashExcluding(@Param("hash") String hash, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE emp_no = #{empNo} AND deleted = 0")
    int countByEmpNo(@Param("empNo") String empNo);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE emp_no = #{empNo} AND deleted = 0 AND id <> #{excludeId}")
    int countByEmpNoExcluding(@Param("empNo") String empNo, @Param("excludeId") Long excludeId);

    /**
     * 某主体在某期间应生成工资的员工。
     *
     * <p>归属判定读 emp_employment 而不是 emp_employee.company_id（详细设计 §4.9）：
     * 解锁重算历史期间时，人员表里的当前归属可能早就变了，直接读会把今天的公司写进历史。
     * 对还没有任职区间数据的老人员，退回用 emp_employee.company_id，避免一笔数据都没有时全员漏算。
     *
     * <p>在职状态只过滤 status=1；离职人员只要离职日落在期间内，离职当月仍要发工资。
     */
    @Select("""
            SELECT e.* FROM emp_employee e
             WHERE e.deleted = 0 AND e.status = 1
               AND e.hire_date <= #{periodEnd}
               AND (e.leave_date IS NULL OR e.leave_date >= #{periodStart})
               AND (
                 EXISTS (SELECT 1 FROM emp_employment m
                          WHERE m.employee_id = e.id AND m.deleted = 0
                            AND m.company_id = #{companyId}
                            AND m.start_date <= #{periodEnd}
                            AND (m.end_date IS NULL OR m.end_date >= #{periodEnd}))
                 OR (NOT EXISTS (SELECT 1 FROM emp_employment m2
                                  WHERE m2.employee_id = e.id AND m2.deleted = 0)
                     AND e.company_id = #{companyId})
               )
             ORDER BY e.emp_no
            """)
    List<EmpEmployeeEntity> findCandidates(@Param("companyId") Long companyId,
                                           @Param("periodStart") LocalDate periodStart,
                                           @Param("periodEnd") LocalDate periodEnd);
}
