package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 核算明细（一行一个工资项）。本表无 deleted 列。 */
@Data
@TableName("pay_record_item")
public class PayRecordItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recordId;
    private String itemCode;
    private String itemName;
    private String category;
    private Integer direction;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    /** FIXED / IMPORT / MANUAL / COMPUTED */
    private String source;
    private Integer sortNo;
}
