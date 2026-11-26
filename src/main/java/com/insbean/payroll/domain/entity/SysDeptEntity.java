package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_dept")
public class SysDeptEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private Long parentId;
    private String deptCode;
    private String deptName;
    private String ancestors;
    private Integer sortNo;
    private Integer status;
    private Integer deleted;
}
