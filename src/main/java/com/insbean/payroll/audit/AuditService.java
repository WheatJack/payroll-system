package com.insbean.payroll.audit;

import com.insbean.payroll.audit.mapper.AuditMapper;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.mapper.AuthMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审计写入。
 *
 * <p>必须记录的高危动作（详细设计 §4.7 第 3 条）：导出、导出查询、解锁、权限变更。
 * 写审计失败不应该阻断业务流程吗？——**应该阻断**。审计是合规要求，
 * 记不下来的操作等于"做过但无法追责"，宁可直接失败。
 *
 * <p>操作人取自当前登录上下文（不是接口入参），避免调用方传错/伪造。
 */
@Service
public class AuditService {

    /** 单字段最大长度。pay_audit_log.old_value/new_value 是 TEXT，超长会写失败。 */
    private static final int MAX_VALUE_LENGTH = 4000;

    private final AuditMapper auditMapper;
    private final AuthMapper authMapper;

    public AuditService(AuditMapper auditMapper, AuthMapper authMapper) {
        this.auditMapper = auditMapper;
        this.authMapper = authMapper;
    }

    /**
     * @param bizType   业务类型：RECORD / PERIOD / EMPLOYEE / IMPORT / EXPORT / VIEW / USER
     * @param bizId     业务主键
     * @param action    动作：CREATE / UPDATE / DELETE / LOCK / UNLOCK / EXPORT / VIEW
     * @param fieldName 变更字段；整条操作可传 null
     * @param oldValue  变更前（敏感值必须先加密/掩码）
     * @param newValue  变更后
     */
    @Transactional
    public void record(String bizType, Long bizId, String action,
                       String fieldName, String oldValue, String newValue) {
        CurrentUser current = CurrentUser.get();

        AuditLogEntity entity = new AuditLogEntity();
        // 集团级账号看到的是跨主体数据，写"本人主体"会让审计看起来已按主体过滤，实际没有
        entity.setCompanyId(current == null || "ALL".equals(current.dataScope())
                ? null : current.companyId());
        entity.setBizType(bizType);
        entity.setBizId(bizId);
        entity.setAction(action);
        entity.setFieldName(fieldName);
        entity.setOldValue(truncate(oldValue));
        entity.setNewValue(truncate(newValue));
        entity.setOperatorId(current == null ? 0L : current.userId());
        entity.setOperatorName(resolveOperatorName(current));
        entity.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(entity);
    }

    private String resolveOperatorName(CurrentUser current) {
        if (current == null) {
            return "system";
        }
        String realName = authMapper.findRealName(current.userId());
        return realName == null ? String.valueOf(current.userId()) : realName;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH);
    }
}
