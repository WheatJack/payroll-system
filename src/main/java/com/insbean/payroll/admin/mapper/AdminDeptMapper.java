package com.insbean.payroll.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AdminDeptMapper extends BaseMapper<SysDeptEntity> {

    @Select("SELECT COUNT(*) FROM sys_dept WHERE parent_id = #{deptId} AND deleted = 0")
    int countChildren(@Param("deptId") Long deptId);

    @Select("SELECT COUNT(*) FROM emp_employee WHERE dept_id = #{deptId} AND deleted = 0")
    int countEmployees(@Param("deptId") Long deptId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE dept_id = #{deptId} AND deleted = 0")
    int countUsers(@Param("deptId") Long deptId);

    @Select("""
            SELECT id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status, deleted
              FROM sys_dept
             WHERE deleted = 0 AND ancestors LIKE CONCAT('%,', #{deptId}, ',%')
            """)
    List<SysDeptEntity> findDescendants(@Param("deptId") Long deptId);

    @Update("UPDATE sys_dept SET ancestors = #{ancestors} WHERE id = #{id}")
    int updateAncestors(@Param("id") Long id, @Param("ancestors") String ancestors);
}
