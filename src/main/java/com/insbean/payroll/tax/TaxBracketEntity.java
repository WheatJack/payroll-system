package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 工资薪金预扣率表（累计应纳税所得额区间）。 */
@Data
@TableName("pay_tax_bracket")
public class TaxBracketEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private BigDecimal yearMin;
    /** null = 无上限 */
    private BigDecimal yearMax;
    private BigDecimal rate;
    private BigDecimal quickDeduction;
    private Integer sortNo;
}
