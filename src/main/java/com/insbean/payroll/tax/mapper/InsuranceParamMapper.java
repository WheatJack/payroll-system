package com.insbean.payroll.tax.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.tax.InsuranceParamEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface InsuranceParamMapper extends BaseMapper<InsuranceParamEntity> {

    /** 城市在指定日期生效的各险种参数（每个险种取 <= 日期的最新一条）。 */
    @Select("""
            SELECT p.* FROM pay_insurance_param p
              JOIN (SELECT insurance_type, MAX(effective_from) AS mf
                      FROM pay_insurance_param
                     WHERE city_code = #{cityCode} AND status = 1 AND effective_from <= #{date}
                     GROUP BY insurance_type) t
                ON t.insurance_type = p.insurance_type AND t.mf = p.effective_from
             WHERE p.city_code = #{cityCode} AND p.status = 1
            """)
    List<InsuranceParamEntity> findEffective(@Param("cityCode") String cityCode,
                                             @Param("date") LocalDate date);
}
