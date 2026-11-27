package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 专项附加扣除（按月额 + 生效区间）。 */
@Data
@TableName("emp_special_deduction")
public class SpecialDeductionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long employeeId;
    private String deductionType;
    private BigDecimal monthlyAmount;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String remark;
}
