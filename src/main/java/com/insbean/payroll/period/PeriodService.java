package com.insbean.payroll.period;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.Money;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.period.dto.PeriodDTO;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.setting.ConfigService;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 工资期间：状态机 + 任务租约。
 *
 * <p>两条硬约束都落在服务端，前端禁用按钮只是体验优化，不是防线：
 * <ul>
 *   <li>{@link #assertWritable} 是所有写接口的第一道调用，LOCKED/ARCHIVED 一律 409；</li>
 *   <li>锁定人不能是本期编制人（C4 内控），代码硬校验，不可配置绕过。</li>
 * </ul>
 */
@Service
public class PeriodService {

    /** 期间计薪天数参数的默认值（月平均计薪天数）。 */
    public static final BigDecimal DEFAULT_HPD = new BigDecimal("21.75");

    private static final String PERIOD_YM_PATTERN = "^[0-9]{4}-(0[1-9]|1[0-2])$";

    private final PeriodMapper periodMapper;
    private final PayRecordMapper recordMapper;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;
    private final ConfigService configService;

    public PeriodService(PeriodMapper periodMapper, PayRecordMapper recordMapper,
                         DataScopeEnforcer dataScope, AuditService auditService,
                         ConfigService configService) {
        this.periodMapper = periodMapper;
        this.recordMapper = recordMapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
        this.configService = configService;
    }

    // ------------------------------------------------------------------ 查询

    public PeriodDTO get(Long periodId) {
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = requirePeriod(periodId);
        return toScopedDto(period, visibleAggregates(List.of(periodId)));
    }

    public List<PeriodDTO> list(Long companyId) {
        dataScope.assertCompanyVisible(companyId);
        List<PayPeriodEntity> periods = periodMapper.selectList(Wrappers.<PayPeriodEntity>lambdaQuery()
                .eq(PayPeriodEntity::getCompanyId, companyId)
                .orderByDesc(PayPeriodEntity::getPeriodYm));
        Map<Long, PeriodAggregate> aggregates = visibleAggregates(
                periods.stream().map(PayPeriodEntity::getId).toList());
        return periods.stream()
                .map(p -> toScopedDto(p, aggregates))
                .toList();
    }

    /**
     * 非集团/公司范围的账号，期间合计必须按**可见记录**重算。
     *
     * <p>pay_period.total_* 是全公司口径，SELF/DEPT/DEPT_SUB 直接下发等于把
     * 全公司工资合计泄露给普通员工。用一次分组查询算出可见口径，避免逐期间 N+1。
     */
    private Map<Long, PeriodAggregate> visibleAggregates(List<Long> periodIds) {
        CurrentUser current = CurrentUser.get();
        if (current == null || periodIds.isEmpty()
                || DataScopeEnforcer.ALL.equals(current.dataScope())
                || DataScopeEnforcer.COMPANY.equals(current.dataScope())) {
            return Map.of();
        }
        var query = Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getDeleted, 0)
                .in(PayRecordEntity::getPeriodId, periodIds)
                .groupBy(PayRecordEntity::getPeriodId);
        dataScope.applyRecordFilter(query);
        Map<Long, PeriodAggregate> result = new java.util.HashMap<>();
        for (Map<String, Object> row : recordMapper.aggregateByPeriod(query)) {
            Long id = Long.valueOf(row.get("period_id").toString());
            result.put(id, new PeriodAggregate(
                    Integer.parseInt(row.get("employee_count").toString()),
                    money(toDecimal(row.get("total_gross"))),
                    money(toDecimal(row.get("total_net")))));
        }
        return result;
    }

    private record PeriodAggregate(int employeeCount, BigDecimal totalGross, BigDecimal totalNet) {
    }

    private static BigDecimal toDecimal(Object value) {
        return value == null ? BigDecimal.ZERO
                : value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private PeriodDTO toScopedDto(PayPeriodEntity period, Map<Long, PeriodAggregate> aggregates) {
        PeriodDTO dto = toDto(period);
        PeriodAggregate aggregate = aggregates.get(period.getId());
        if (aggregate == null) {
            return dto;
        }
        return new PeriodDTO(dto.id(), dto.companyId(), dto.periodYm(), dto.status(),
                aggregate.employeeCount(), aggregate.totalGross(), aggregate.totalNet(),
                dto.hpd(), dto.compiledBy(), dto.lockedBy(), dto.lockedAt(), dto.unlockReason());
    }

    public PayPeriodEntity requirePeriod(Long periodId) {
        PayPeriodEntity period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BizException(404, "工资期间不存在");
        }
        return period;
    }

    // ------------------------------------------------------------------ 状态机

    /**
     * 所有写操作的准入校验。已锁定/已归档一律拒绝，且不产生任何数据变更
     * ——调用方必须先过这一关，再做真正的事务性写入。
     */
    public void assertWritable(Long periodId) {
        assertWritable(requirePeriod(periodId));
    }

    /**
     * 写事务内使用：加行锁读取期间再校验状态。
     *
     * <p>普通 SELECT 读到的是快照，校验通过到真正写入之间期间可能被锁定，
     * 于是"锁定后写入"仍然能穿过去。写路径必须用 {@code SELECT ... FOR UPDATE}，
     * 让锁定事务与写入事务在行锁上排队。
     */
    public void assertWritableForUpdate(Long periodId) {
        PayPeriodEntity period = periodMapper.selectForUpdate(periodId);
        if (period == null) {
            throw new BizException(404, "工资期间不存在");
        }
        assertWritable(period);
    }

    private void assertWritable(PayPeriodEntity period) {
        PeriodStatus status = PeriodStatus.of(period.getStatus());
        if (status == PeriodStatus.LOCKED) {
            throw new BizException(409, "期间已锁定，禁止修改");
        }
        if (status == PeriodStatus.ARCHIVED) {
            throw new BizException(409, "期间已归档，禁止修改");
        }
    }

    /**
     * 生成完成后把期间推进到 EDITING 并记下编制人。
     * 编制人用于锁定时校验"编制人 ≠ 锁定人"。
     */
    @Transactional
    public void markEditing(Long periodId, Long operatorId) {
        PayPeriodEntity period = requirePeriod(periodId);
        assertWritable(periodId);
        if (PeriodStatus.of(period.getStatus()) == PeriodStatus.DRAFT) {
            period.setStatus(PeriodStatus.EDITING.name());
        }
        period.setCompiledBy(operatorId);
        periodMapper.updateById(period);
    }

    /** 锁定期间。只有 EDITING 可以锁定，且锁定人不能是编制人（C4）。 */
    @Transactional
    public PeriodDTO lock(Long periodId, Long userId) {
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = requirePeriod(periodId);
        PeriodStatus status = PeriodStatus.of(period.getStatus());
        if (status == PeriodStatus.LOCKED) {
            throw new BizException(409, "期间已锁定，无需重复锁定");
        }
        if (status == PeriodStatus.ARCHIVED) {
            throw new BizException(409, "期间已归档，不能锁定");
        }
        if (status != PeriodStatus.EDITING) {
            throw new BizException(409, "期间尚未生成，只有编制中的期间可以锁定");
        }
        if (period.getCompiledBy() != null && period.getCompiledBy().equals(userId)) {
            throw new BizException(400, "锁定人不能是本期间的编制人，请由他人复核锁定");
        }
        period.setStatus(PeriodStatus.LOCKED.name());
        period.setLockedBy(userId);
        period.setLockedAt(LocalDateTime.now());
        periodMapper.updateById(period);
        // 锁定是高危动作，必须留痕（谁在什么时候锁了哪一期）
        auditService.record("PERIOD", periodId, "LOCK", "status", status.name(), PeriodStatus.LOCKED.name());
        return toDto(period);
    }

    /** 解锁（高危）：必须填写原因，回到 EDITING；ARCHIVED 不可解锁。 */
    @Transactional
    public PeriodDTO unlock(Long periodId, Long userId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(400, "解锁原因必填");
        }
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = requirePeriod(periodId);
        PeriodStatus status = PeriodStatus.of(period.getStatus());
        if (status == PeriodStatus.ARCHIVED) {
            throw new BizException(409, "期间已归档，不可解锁");
        }
        if (status != PeriodStatus.LOCKED) {
            throw new BizException(409, "只有已锁定的期间可以解锁");
        }
        period.setStatus(PeriodStatus.EDITING.name());
        period.setUnlockReason(reason.trim());
        period.setLockedBy(null);
        period.setLockedAt(null);
        periodMapper.updateById(period);
        // 解锁是最高危的动作（重开已锁定期间），原因必须进审计
        auditService.record("PERIOD", periodId, "UNLOCK", "unlock_reason",
                PeriodStatus.LOCKED.name(), reason.trim());
        return toDto(period);
    }

    // ------------------------------------------------------------------ 任务租约

    /**
     * 抢占期间的处理租约。返回 false 表示已有别的任务在处理（或还没过期），
     * 调用方应当立刻失败而不是排队——两个并发生成会互相覆盖数据。
     */
    public boolean tryAcquire(Long periodId, String owner, Duration ttl) {
        if (owner == null || owner.isBlank()) {
            throw new BizException(400, "租约持有者标识必填");
        }
        LocalDateTime expireAt = LocalDateTime.now().plus(ttl);
        return periodMapper.tryAcquire(periodId, owner, expireAt) > 0;
    }

    public void release(Long periodId, String owner) {
        periodMapper.release(periodId, owner);
    }

    // ------------------------------------------------------------------ 新建

    /** 按 (companyId, periodYm) 幂等：已存在时直接返回已有期间，不报错。 */
    @Transactional
    public PeriodDTO create(PeriodCreateReq req, Long defaultCompanyId) {
        String periodYm = req.periodYm();
        if (periodYm == null || !periodYm.matches(PERIOD_YM_PATTERN)) {
            throw new BizException(400, "期间格式必须为 YYYY-MM：" + periodYm);
        }
        Long companyId = req.companyId() != null ? req.companyId() : defaultCompanyId;
        dataScope.assertCompanyVisible(companyId);
        PayPeriodEntity existing = findByCompanyAndYm(companyId, periodYm);
        if (existing != null) {
            return toDto(existing);
        }

        PayPeriodEntity entity = new PayPeriodEntity();
        entity.setCompanyId(companyId);
        entity.setPeriodYm(periodYm);
        entity.setStatus(PeriodStatus.DRAFT.name());
        entity.setEmployeeCount(0);
        entity.setTotalGross(BigDecimal.ZERO);
        entity.setTotalNet(BigDecimal.ZERO);
        // 系统参数作为新期间的默认计薪天数；已有期间保留自己的 hpd（期间级参数优先）
        BigDecimal configuredHpd = configService.getDecimal(
                ConfigService.MONTHLY_PAYABLE_DAYS, DEFAULT_HPD);
        entity.setHpd(configuredHpd != null && configuredHpd.signum() > 0
                ? configuredHpd : DEFAULT_HPD);
        entity.setVersion(0);
        try {
            periodMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发建同一期间：唯一键兜底，回查后当作幂等返回
            PayPeriodEntity raced = findByCompanyAndYm(companyId, periodYm);
            if (raced == null) {
                throw e;
            }
            return toDto(raced);
        }
        return toDto(entity);
    }

    private PayPeriodEntity findByCompanyAndYm(Long companyId, String periodYm) {
        return periodMapper.selectOne(Wrappers.<PayPeriodEntity>lambdaQuery()
                .eq(PayPeriodEntity::getCompanyId, companyId)
                .eq(PayPeriodEntity::getPeriodYm, periodYm)
                .last("LIMIT 1"));
    }

    // ------------------------------------------------------------------ 工具

    public static LocalDate periodStart(String periodYm) {
        return YearMonth.parse(periodYm).atDay(1);
    }

    public static LocalDate periodEnd(String periodYm) {
        return YearMonth.parse(periodYm).atEndOfMonth();
    }

    public static PeriodDTO toDto(PayPeriodEntity e) {
        return new PeriodDTO(
                e.getId(),
                e.getCompanyId(),
                e.getPeriodYm(),
                e.getStatus(),
                e.getEmployeeCount() == null ? 0 : e.getEmployeeCount(),
                money(e.getTotalGross()),
                money(e.getTotalNet()),
                e.getHpd() == null ? DEFAULT_HPD : e.getHpd(),
                e.getCompiledBy(),
                e.getLockedBy(),
                e.getLockedAt(),
                e.getUnlockReason());
    }

    private static BigDecimal money(BigDecimal value) {
        return Money.scaled(value);
    }
}
