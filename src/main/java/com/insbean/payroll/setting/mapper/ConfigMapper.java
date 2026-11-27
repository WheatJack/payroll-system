package com.insbean.payroll.setting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.setting.ConfigEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ConfigMapper extends BaseMapper<ConfigEntity> {

    @Select("SELECT * FROM sys_config WHERE config_key = #{key}")
    ConfigEntity findByKey(@Param("key") String key);
}
