package com.insbean.payroll.employee.dto;

/** 证件号/手机号明文（仅专用接口返回，列表与详情永远只有掩码）。 */
public record EmployeeSensitiveDTO(String idNo, String mobile) {
}
