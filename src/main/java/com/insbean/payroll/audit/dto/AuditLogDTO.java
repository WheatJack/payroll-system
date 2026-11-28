package com.insbean.payroll.audit.dto;

import java.time.LocalDateTime;

/** 审计流水行。金额 old/new 为 DECIMAL 字符串；敏感值从不进入审计。 */
public record AuditLogDTO(
        Long id,
        String bizType,
        Long bizId,
        String action,
        String fieldName,
        String oldValue,
        String newValue,
        String operatorName,
        String ip,
        LocalDateTime createdAt
) {
}
