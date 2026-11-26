package com.insbean.payroll.period;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.period.dto.PeriodDTO;
import com.insbean.payroll.period.mapper.PeriodMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

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

    public PeriodService(PeriodMapper periodMapper) {
        this.periodMapper = periodMapper;
    }

    // ------------------------------------------------------------------ 查询

    public PeriodDTO get(Long periodId) {
        return toDto(requirePeriod(periodId));
    }

    public List<PeriodDTO> list(Long companyId) {
        return periodMapper.selectList(Wrappers.<PayPeriodEntity>lambdaQuery()
                        .eq(PayPeriodEntity::getCompanyId, companyId)
                        .orderByDesc(PayPeriodEntity::getPeriodYm))
                .stream()
                .map(PeriodService::toDto)
                .toList();
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
        PayPeriodEntity period = requirePeriod(periodId);
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
        return toDto(period);
    }

    /** 解锁（高危）：必须填写原因，回到 EDITING；ARCHIVED 不可解锁。 */
    @Transactional
    public PeriodDTO unlock(Long periodId, Long userId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(400, "解锁原因必填");
        }
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
        entity.setHpd(DEFAULT_HPD);
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
                e.getHpd() == null ? DEFAULT_HPD : e.getHpd());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null
                ? new BigDecimal("0.00")
                : value.setScale(2, RoundingMode.HALF_UP);
    }
}
