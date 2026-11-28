package com.insbean.payroll.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insbean.payroll.audit.dto.AuditLogDTO;
import com.insbean.payroll.audit.dto.AuditLogPageDTO;
import com.insbean.payroll.audit.mapper.AuditMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 审计流水查询。审计一直在写，没有查询入口等于白写——管理员查"谁解锁的、为什么"
 * 不该只能进 MySQL。
 */
@Service
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditMapper auditMapper;

    public AuditQueryService(AuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public AuditLogPageDTO page(String bizType, String action, String operator,
                                LocalDate start, LocalDate end, long current, long size) {
        long pageNo = Math.max(1, current);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        var query = Wrappers.<AuditLogEntity>lambdaQuery()
                .eq(bizType != null && !bizType.isBlank(), AuditLogEntity::getBizType, bizType)
                .eq(action != null && !action.isBlank(), AuditLogEntity::getAction, action)
                .like(operator != null && !operator.isBlank(),
                        AuditLogEntity::getOperatorName, operator)
                .ge(start != null, AuditLogEntity::getCreatedAt,
                        start == null ? null : start.atStartOfDay())
                .lt(end != null, AuditLogEntity::getCreatedAt,
                        end == null ? null : end.plusDays(1).atStartOfDay())
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .orderByDesc(AuditLogEntity::getId);

        Page<AuditLogEntity> page = auditMapper.selectPage(new Page<>(pageNo, pageSize), query);
        List<AuditLogDTO> records = page.getRecords().stream()
                .map(e -> new AuditLogDTO(e.getId(), e.getBizType(), e.getBizId(), e.getAction(),
                        e.getFieldName(), e.getOldValue(), e.getNewValue(),
                        e.getOperatorName(), e.getIp(), e.getCreatedAt()))
                .toList();
        return new AuditLogPageDTO(records, (int) page.getTotal(),
                (int) page.getCurrent(), (int) page.getSize());
    }
}
