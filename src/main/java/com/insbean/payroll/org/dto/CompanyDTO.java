package com.insbean.payroll.org.dto;

/** 契约 components.schemas.Company。 */
public record CompanyDTO(
        Long id,
        String companyCode,
        String companyName,
        Integer status
) {
}
