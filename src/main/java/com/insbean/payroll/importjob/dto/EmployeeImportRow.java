package com.insbean.payroll.importjob.dto;

import com.insbean.payroll.employee.dto.EmployeeSaveReq;

import java.math.BigDecimal;

/** 预校验通过的一行：人员建档请求 + 基本工资（提交时同时建薪酬档案）。 */
public record EmployeeImportRow(int rowNo, EmployeeSaveReq request, BigDecimal baseSalary) {
}
