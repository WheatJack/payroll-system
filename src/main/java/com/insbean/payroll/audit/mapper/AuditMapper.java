package com.insbean.payroll.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.audit.AuditLogEntity;

/**
 * 审计日志只提供插入与查询：故意不暴露 update/delete，
 * 避免业务代码"顺手"改写历史流水。
 */
public interface AuditMapper extends BaseMapper<AuditLogEntity> {
}
