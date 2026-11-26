package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_company")
public class SysCompanyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String companyCode;
    private String companyName;
    private Integer status;
    private Integer deleted;
}
