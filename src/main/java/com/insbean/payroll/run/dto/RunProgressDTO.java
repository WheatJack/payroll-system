package com.insbean.payroll.run.dto;

import java.util.List;
import java.util.Map;

/** 契约 components.schemas.RunProgress。skipped 的键是工号，值是跳过原因。 */
public record RunProgressDTO(
        String status,
        int total,
        int success,
        int failed,
        List<RunErrorDTO> errors,
        Map<String, String> skipped
) {
}
