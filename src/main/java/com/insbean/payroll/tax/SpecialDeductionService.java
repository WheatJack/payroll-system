package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.DataScopeEnforcer;
import com.insbean.payroll.tax.dto.SpecialDeductionDTO;
import com.insbean.payroll.tax.dto.SpecialDeductionSaveReq;
import com.insbean.payroll.tax.mapper.SpecialDeductionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 专项附加扣除维护。 */
@Service
public class SpecialDeductionService {

    private static final Set<String> TYPES = Set.of(
            "CHILD_EDU", "INFANT_CARE", "CONTINUE_EDU",
            "HOUSING_LOAN", "HOUSING_RENT", "ELDERLY_CARE");

    private final SpecialDeductionMapper mapper;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;

    public SpecialDeductionService(SpecialDeductionMapper mapper, DataScopeEnforcer dataScope,
                                   AuditService auditService) {
        this.mapper = mapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    public List<SpecialDeductionDTO> list(Long employeeId) {
        dataScope.assertEmployeeVisible(employeeId);
        return mapper.listByEmployee(employeeId).stream()
                .map(SpecialDeductionService::toDto)
                .toList();
    }

    /** 新增/修改：同一 (员工, 类型, 生效日) 视为覆盖。 */
    @Transactional
    public SpecialDeductionDTO save(Long employeeId, SpecialDeductionSaveReq req) {
        dataScope.assertEmployeeVisible(employeeId);
        validate(req);
        SpecialDeductionEntity existing = mapper.selectOne(
                Wrappers.<SpecialDeductionEntity>lambdaQuery()
                        .eq(SpecialDeductionEntity::getEmployeeId, employeeId)
                        .eq(SpecialDeductionEntity::getDeductionType, req.deductionType())
                        .eq(SpecialDeductionEntity::getEffectiveFrom, req.effectiveFrom())
                        .last("LIMIT 1"));
        SpecialDeductionEntity entity = existing == null ? new SpecialDeductionEntity() : existing;
        entity.setEmployeeId(employeeId);
        entity.setDeductionType(req.deductionType());
        entity.setMonthlyAmount(req.monthlyAmount());
        entity.setEffectiveFrom(req.effectiveFrom());
        entity.setEffectiveTo(req.effectiveTo());
        entity.setRemark(req.remark());
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        auditService.record("EMPLOYEE", employeeId, "UPDATE", "special_deduction", null,
                req.deductionType() + "=" + req.monthlyAmount() + ";from=" + req.effectiveFrom());
        return toDto(entity);
    }

    @Transactional
    public void delete(Long id) {
        SpecialDeductionEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "专项附加扣除不存在");
        }
        dataScope.assertEmployeeVisible(entity.getEmployeeId());
        mapper.deleteById(id);
        auditService.record("EMPLOYEE", entity.getEmployeeId(), "DELETE", "special_deduction",
                entity.getDeductionType(), null);
    }

    private static void validate(SpecialDeductionSaveReq req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (req.deductionType() == null || !TYPES.contains(req.deductionType())) {
            throw new BizException(400, "deductionType 不合法：" + req.deductionType());
        }
        if (req.monthlyAmount() == null || req.monthlyAmount().signum() <= 0) {
            throw new BizException(400, "monthlyAmount 必须大于 0");
        }
        if (req.effectiveFrom() == null) {
            throw new BizException(400, "effectiveFrom 必填");
        }
        if (req.effectiveTo() != null && req.effectiveTo().isBefore(req.effectiveFrom())) {
            throw new BizException(400, "effectiveTo 不能早于 effectiveFrom");
        }
    }

    private static SpecialDeductionDTO toDto(SpecialDeductionEntity e) {
        return new SpecialDeductionDTO(e.getId(), e.getEmployeeId(), e.getDeductionType(),
                e.getMonthlyAmount(), e.getEffectiveFrom(), e.getEffectiveTo(), e.getRemark());
    }
}
