package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 工资核算记录（含人员快照）。active_key 是数据库生成列，实体不映射。 */
@Data
@TableName("pay_record")
public class PayRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long periodId;
    private String periodYm;
    private Long companyId;
    private Long employeeId;
    private String empNo;
    private String empName;
    private Long deptId;
    private String deptName;
    private String jobLevel;
    private String employeeType;
    private LocalDate hireDate;
    private BigDecimal seniorityYears;
    private BigDecimal baseSalarySnapshot;
    private BigDecimal grossAmount;
    private BigDecimal deductionAmount;
    private BigDecimal netAmount;
    private String recordStatus;
    private Integer versionNo;
    private Long supersededBy;
    private String remark;
    private Long createdBy;
    private Integer deleted;
}
