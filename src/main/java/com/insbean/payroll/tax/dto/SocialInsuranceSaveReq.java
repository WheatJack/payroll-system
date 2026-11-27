package com.insbean.payroll.tax.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** socialBase 为空 = 按近 12 期平均应发自动算；housingFundRate 为空 = 用城市默认。 */
public record SocialInsuranceSaveReq(
        String cityCode,
        BigDecimal socialBase,
        BigDecimal housingFundRate,
        LocalDate effectiveFrom
) {
}
