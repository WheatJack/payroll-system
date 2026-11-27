package com.insbean.payroll.tax.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.tax.TaxBracketEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

public interface TaxBracketMapper extends BaseMapper<TaxBracketEntity> {

    /** 按累计应纳税所得额命中档位（year_min 含、year_max 不含，NULL 为无上限）。 */
    @Select("""
            SELECT * FROM pay_tax_bracket
             WHERE #{taxable} >= year_min
               AND (year_max IS NULL OR #{taxable} < year_max)
             ORDER BY sort_no
             LIMIT 1
            """)
    TaxBracketEntity findByTaxable(@Param("taxable") BigDecimal taxable);
}
