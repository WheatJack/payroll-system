package com.insbean.payroll.org.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.SysCompanyEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CompanyMapper extends BaseMapper<SysCompanyEntity> {

    @Select("SELECT company_name FROM sys_company WHERE id = #{id} AND deleted = 0")
    String findNameById(@Param("id") Long id);
}
