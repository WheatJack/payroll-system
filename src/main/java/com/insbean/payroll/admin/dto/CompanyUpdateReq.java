package com.insbean.payroll.admin.dto;

/** 契约 components.schemas.CompanyUpdateRequest。 */
public record CompanyUpdateReq(String companyCode, String companyName, Integer status) {
}
