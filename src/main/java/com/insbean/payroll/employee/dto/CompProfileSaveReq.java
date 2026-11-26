package com.insbean.payroll.employee.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 薪酬档案新增/修改请求。
 *
 * <p>界面上不暴露"版本"概念：保存一次 = 从 effectiveFrom 起换成新的金额，
 * 后端负责把上一条区间截断。历史工资表不受影响，因为核算记录存的是快照。
 *
 * <p>employeeId 在 PUT 时可以不传（由路径上的档案 id 反查），因此不给必填注解，
 * 由 Service 在新增分支里校验。
 */
public record CompProfileSaveReq(
        Long id,
        Long employeeId,
        LocalDate effectiveFrom,
        BigDecimal baseSalary,
        @Size(max = 255) String changeReason,
        List<CompProfileItemDTO> items
) {
}
