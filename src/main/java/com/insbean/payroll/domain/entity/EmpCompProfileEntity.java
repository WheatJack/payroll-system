package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("emp_comp_profile")
public class EmpCompProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long employeeId;
    private LocalDate effectiveFrom;
    /** null = 至今 */
    private LocalDate effectiveTo;
    private BigDecimal baseSalary;
    private String changeReason;
    private Integer status;
    private Integer deleted;
}
