package com.insbean.payroll.employee.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 人员新增/修改请求。
 *
 * <p>新增与修改共用一个结构：修改时只提交非 null 字段（证件号/手机号不传即不改），
 * 新增时的必填校验放在 Service 里做，因为同样是 empNo，修改时可以省略。
 * 明文证件号只在这一层出现，落库前立刻加密 + 盲索引。
 *
 * @param effectiveDate 调动生效日（仅当本次修改改变了 companyId/deptId 时才使用）。
 *                      不传时默认今天——但显式传入才能对上 HR 实际生效的日期，
 *                      历史上写死 LocalDate.now() 会让补录的调动落到错误的区间。
 */
public record EmployeeSaveReq(
        Long companyId,
        @Size(max = 32) String empNo,
        @Size(max = 64) String name,
        @Min(0) @Max(2) Integer gender,
        @Size(max = 16) String idType,
        @Size(max = 32) String idNo,
        @Size(max = 20) String mobile,
        String employeeType,
        @Size(max = 32) String jobLevel,
        @Size(max = 64) String positionName,
        Long deptId,
        LocalDate hireDate,
        LocalDate workStartDate,
        LocalDate leaveDate,
        @Min(1) @Max(3) Integer status,
        @Size(max = 255) String remark,
        LocalDate effectiveDate
) {

    /** 兼容不传调动生效日的调用方（默认今天）。 */
    public EmployeeSaveReq(Long companyId,
                           String empNo,
                           String name,
                           Integer gender,
                           String idType,
                           String idNo,
                           String mobile,
                           String employeeType,
                           String jobLevel,
                           String positionName,
                           Long deptId,
                           LocalDate hireDate,
                           LocalDate workStartDate,
                           LocalDate leaveDate,
                           Integer status,
                           String remark) {
        this(companyId, empNo, name, gender, idType, idNo, mobile, employeeType, jobLevel,
                positionName, deptId, hireDate, workStartDate, leaveDate, status, remark, null);
    }
}
