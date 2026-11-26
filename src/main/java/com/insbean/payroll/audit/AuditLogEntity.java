package com.insbean.payroll.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（只增不改）。
 *
 * <p>本表没有 deleted 列，也没有 updated_* 列——审计记录一旦写入就不允许修改，
 * 这是合规的基本要求。对应的 Mapper 也因此不提供任何更新方法。
 */
@Data
@TableName("pay_audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    /** RECORD / PERIOD / EMPLOYEE / IMPORT / EXPORT / VIEW / USER */
    private String bizType;
    private Long bizId;
    /** CREATE / UPDATE / DELETE / LOCK / UNLOCK / EXPORT / VIEW */
    private String action;
    private String fieldName;
    /** 敏感值必须加密后再写入 */
    private String oldValue;
    private String newValue;
    private Long operatorId;
    private String operatorName;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
