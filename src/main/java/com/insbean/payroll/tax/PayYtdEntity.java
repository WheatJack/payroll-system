package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 个税年度累计台账：按员工 × 年度一行。 */
@Data
@TableName("pay_ytd")
public class PayYtdEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long employeeId;
    private Integer taxYear;
    private Long companyId;
    private BigDecimal cumIncome;
    private BigDecimal cumSocial;
    private BigDecimal cumFund;
    private BigDecimal cumSpecialDeduction;
    private BigDecimal cumTax;
    /** 本年度已计薪月份数（不含未锁定期间）；减除费用按它+1 计算。 */
    private Integer paidMonths;
    private String lastPeriodYm;
}
