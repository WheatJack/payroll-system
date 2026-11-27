package com.insbean.payroll.tax.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.tax.PayYtdEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface PayYtdMapper extends BaseMapper<PayYtdEntity> {

    @Select("SELECT * FROM pay_ytd WHERE employee_id = #{employeeId} AND tax_year = #{taxYear}")
    PayYtdEntity findByEmployeeAndYear(@Param("employeeId") Long employeeId,
                                       @Param("taxYear") int taxYear);

    /** 锁定时累加（不存在则新建当年行）。 */
    @Insert("""
            INSERT INTO pay_ytd
              (employee_id, tax_year, company_id, cum_income, cum_social, cum_fund,
               cum_special_deduction, cum_tax, paid_months, last_period_ym, updated_at)
            VALUES (#{employeeId}, #{taxYear}, #{companyId}, #{income}, #{social}, #{fund},
                    #{special}, #{tax}, 1, #{lastPeriodYm}, NOW())
            ON DUPLICATE KEY UPDATE
              company_id = VALUES(company_id),
              cum_income = cum_income + VALUES(cum_income),
              cum_social = cum_social + VALUES(cum_social),
              cum_fund = cum_fund + VALUES(cum_fund),
              cum_special_deduction = cum_special_deduction + VALUES(cum_special_deduction),
              cum_tax = cum_tax + VALUES(cum_tax),
              paid_months = paid_months + 1,
              last_period_ym = VALUES(last_period_ym),
              updated_at = NOW()
            """)
    int addContribution(@Param("employeeId") Long employeeId,
                        @Param("taxYear") int taxYear,
                        @Param("companyId") Long companyId,
                        @Param("income") BigDecimal income,
                        @Param("social") BigDecimal social,
                        @Param("fund") BigDecimal fund,
                        @Param("special") BigDecimal special,
                        @Param("tax") BigDecimal tax,
                        @Param("lastPeriodYm") String lastPeriodYm);

    /** 解锁时减回（GREATEST 防止舍入造成的负数）。 */
    @Update("""
            UPDATE pay_ytd
               SET cum_income = GREATEST(cum_income - #{income}, 0),
                   cum_social = GREATEST(cum_social - #{social}, 0),
                   cum_fund = GREATEST(cum_fund - #{fund}, 0),
                   cum_special_deduction = GREATEST(cum_special_deduction - #{special}, 0),
                   cum_tax = GREATEST(cum_tax - #{tax}, 0),
                   paid_months = GREATEST(paid_months - 1, 0),
                   last_period_ym = #{lastPeriodYm},
                   updated_at = NOW()
             WHERE employee_id = #{employeeId} AND tax_year = #{taxYear}
            """)
    int subtractContribution(@Param("employeeId") Long employeeId,
                             @Param("taxYear") int taxYear,
                             @Param("income") BigDecimal income,
                             @Param("social") BigDecimal social,
                             @Param("fund") BigDecimal fund,
                             @Param("special") BigDecimal special,
                             @Param("tax") BigDecimal tax,
                             @Param("lastPeriodYm") String lastPeriodYm);
}
