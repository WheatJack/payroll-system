package com.insbean.payroll.employee.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EmployeeMapper extends BaseMapper<EmpEmployeeEntity> {

    @Select("SELECT COUNT(*) FROM emp_employee WHERE id_no_hash = #{hash} AND deleted = 0")
    int countByIdNoHash(@Param("hash") String hash);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE id_no_hash = #{hash} AND deleted = 0 AND id <> #{excludeId}")
    int countByIdNoHashExcluding(@Param("hash") String hash, @Param("excludeId") Long excludeId);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE emp_no = #{empNo} AND deleted = 0")
    int countByEmpNo(@Param("empNo") String empNo);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE emp_no = #{empNo} AND deleted = 0 AND id <> #{excludeId}")
    int countByEmpNoExcluding(@Param("empNo") String empNo, @Param("excludeId") Long excludeId);
}
