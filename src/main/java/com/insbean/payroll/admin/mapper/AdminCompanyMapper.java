package com.insbean.payroll.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.SysCompanyEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminCompanyMapper extends BaseMapper<SysCompanyEntity> {

    @Select("SELECT COUNT(*) FROM sys_company WHERE company_code = #{companyCode} AND deleted = 0")
    int countByCode(@Param("companyCode") String companyCode);

    @Select("""
            SELECT COUNT(*) FROM sys_company
             WHERE company_code = #{companyCode} AND id <> #{excludeId} AND deleted = 0
            """)
    int countByCodeExcluding(@Param("companyCode") String companyCode, @Param("excludeId") Long excludeId);
}
