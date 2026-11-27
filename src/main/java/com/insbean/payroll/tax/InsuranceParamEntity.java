package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 城市 × 险种 × 生效日的个人比例与缴费基数上下限。 */
@Data
@TableName("pay_insurance_param")
public class InsuranceParamEntity {

    public static final String PENSION = "PENSION";
    public static final String MEDICAL = "MEDICAL";
    public static final String UNEMPLOYMENT = "UNEMPLOYMENT";
    public static final String HOUSING_FUND = "HOUSING_FUND";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String cityCode;
    private String cityName;
    private String insuranceType;
    private BigDecimal personalRate;
    private BigDecimal baseMin;
    private BigDecimal baseMax;
    /** 固定附加金额，如医疗大病 3 元 */
    private BigDecimal extraAmount;
    private LocalDate effectiveFrom;
    private Integer status;
}
