package com.insbean.payroll.audit.dto;

import java.util.List;

/** 审计分页结果（total/current/size 为计数，必须序列化成 JSON number）。 */
public record AuditLogPageDTO(List<AuditLogDTO> records, int total, int current, int size) {
}
