package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("emp_comp_profile_item")
public class EmpCompProfileItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long profileId;
    private String itemCode;
    private BigDecimal amount;
}
