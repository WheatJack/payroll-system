package com.insbean.payroll.run.dto;

/** 生成跳过明细：工号、姓名、原因。 */
public record RunSkipDetail(String empNo, String empName, String reason) {
}
