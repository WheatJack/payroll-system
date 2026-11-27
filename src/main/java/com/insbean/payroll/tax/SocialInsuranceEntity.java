package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 员工参保信息（带生效区间）。 */
@Data
@TableName("emp_social_insurance")
public class SocialInsuranceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long employeeId;
    private String cityCode;
    /** null = 按规则自动计算（近 12 期平均应发） */
    private BigDecimal socialBase;
    /** null = 用城市默认公积金比例 */
    private BigDecimal housingFundRate;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
