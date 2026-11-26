package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 工资期间。注意本表没有 deleted 列（期间不做软删除）。 */
@Data
@TableName("pay_period")
public class PayPeriodEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long companyId;
    private String periodYm;
    private String status;
    private Integer employeeCount;
    private BigDecimal totalGross;
    private BigDecimal totalNet;
    private BigDecimal hpd;
    private Long compiledBy;
    private Long lockedBy;
    private LocalDateTime lockedAt;
    private String unlockReason;
    private String processingOwner;
    private LocalDateTime processingExpireAt;
    private Integer version;
    /**
     * 生成时的工资表列快照（JSON）。
     * 导出必须用它而不是当前启用科目：明细里可能含已停用科目，按当前字典导列会和合计对不上。
     */
    private String columnSnapshot;
}
