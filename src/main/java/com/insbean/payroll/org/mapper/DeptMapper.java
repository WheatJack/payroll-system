package com.insbean.payroll.org.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DeptMapper extends BaseMapper<SysDeptEntity> {

    @Select("SELECT dept_name FROM sys_dept WHERE id = #{id} AND deleted = 0")
    String findNameById(@Param("id") Long id);
}
