package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 员工在各法人主体的任职区间；核算时的归属依据（详细设计 §4.9）。 */
@Data
@TableName("emp_employment")
public class EmpEmploymentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long employeeId;
    private Long companyId;
    private Long deptId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String changeType;
    private String remark;
    private Integer deleted;
}
