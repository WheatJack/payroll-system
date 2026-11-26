package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("pay_item")
public class PayItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 0 = 集团通用科目 */
    private Long companyId;
    private String itemCode;
    private String itemName;
    private String category;
    /** 1 增加 / 2 减少 */
    private Integer direction;
    private String dataType;
    private Integer precisionScale;
    private Integer includeInGross;
    private Integer editable;
    private Integer importable;
    private Integer sortNo;
    private Integer status;
    private Integer deleted;
}
