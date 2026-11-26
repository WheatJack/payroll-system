package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 人员主数据。证件号/手机号只存密文与盲索引，实体里不出现明文。 */
@Data
@TableName("emp_employee")
public class EmpEmployeeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String empNo;
    private String name;
    private Integer gender;
    private String idType;
    private byte[] idNoCipher;
    private String idNoHash;
    private Integer idNoHashVersion;
    private String idNoMask;
    private byte[] mobileCipher;
    private String mobileHash;
    private String mobileMask;
    private String employeeType;
    private String jobLevel;
    private String positionName;
    private Long deptId;
    private LocalDate hireDate;
    private LocalDate workStartDate;
    private LocalDate leaveDate;
    private Integer status;
    private String remark;
    private Integer deleted;
}
