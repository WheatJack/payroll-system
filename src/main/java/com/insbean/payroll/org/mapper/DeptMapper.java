package com.insbean.payroll.org.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DeptMapper extends BaseMapper<SysDeptEntity> {

    @Select("SELECT dept_name FROM sys_dept WHERE id = #{id} AND deleted = 0")
    String findNameById(@Param("id") Long id);

    /**
     * 某部门及其全部下级部门 id（含自身）。
     * ancestors 是 ",1,2,3," 形式的祖先路径，所以"下级"等价于路径里含有 ",本部门id,"。
     */
    @Select("""
            SELECT id FROM sys_dept
             WHERE deleted = 0
               AND (id = #{deptId} OR ancestors LIKE CONCAT('%,', #{deptId}, ',%'))
            """)
    List<Long> findSubtreeIds(@Param("deptId") Long deptId);
}
