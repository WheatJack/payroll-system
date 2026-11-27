package com.insbean.payroll.tax;

import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import com.insbean.payroll.tax.dto.InsuranceParamDTO;
import com.insbean.payroll.tax.dto.SocialInsuranceDTO;
import com.insbean.payroll.tax.dto.SocialInsuranceSaveReq;
import com.insbean.payroll.tax.mapper.InsuranceParamMapper;
import com.insbean.payroll.tax.mapper.SocialInsuranceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 员工参保信息维护（带生效区间；已锁定期间的历史段不允许修改）。 */
@Service
public class SocialInsuranceService {

    private final SocialInsuranceMapper socialMapper;
    private final InsuranceParamMapper paramMapper;
    private final EmployeeMapper employeeMapper;
    private final PeriodMapper periodMapper;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;

    public SocialInsuranceService(SocialInsuranceMapper socialMapper,
                                  InsuranceParamMapper paramMapper,
                                  EmployeeMapper employeeMapper,
                                  PeriodMapper periodMapper,
                                  DataScopeEnforcer dataScope,
                                  AuditService auditService) {
        this.socialMapper = socialMapper;
        this.paramMapper = paramMapper;
        this.employeeMapper = employeeMapper;
        this.periodMapper = periodMapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    public List<SocialInsuranceDTO> timeline(Long employeeId) {
        dataScope.assertEmployeeVisible(employeeId);
        return socialMapper.listByEmployee(employeeId).stream()
                .map(SocialInsuranceService::toDto)
                .toList();
    }

    @Transactional
    public SocialInsuranceDTO create(Long employeeId, SocialInsuranceSaveReq req) {
        dataScope.assertEmployeeVisible(employeeId);
        validate(req);
        SocialInsuranceEntity entity = new SocialInsuranceEntity();
        entity.setEmployeeId(employeeId);
        entity.setCityCode(req.cityCode().trim().toUpperCase());
        entity.setSocialBase(req.socialBase());
        entity.setHousingFundRate(req.housingFundRate());
        entity.setEffectiveFrom(req.effectiveFrom());
        try {
            socialMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "该员工同生效日已有参保信息");
        }
        auditService.record("EMPLOYEE", employeeId, "UPDATE", "social_insurance", null,
                "city=" + entity.getCityCode() + ";from=" + entity.getEffectiveFrom());
        return toDto(entity);
    }

    @Transactional
    public SocialInsuranceDTO update(Long id, SocialInsuranceSaveReq req) {
        SocialInsuranceEntity entity = socialMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "参保信息不存在");
        }
        dataScope.assertEmployeeVisible(entity.getEmployeeId());
        validate(req);
        assertNotLocked(entity.getEmployeeId(), req.effectiveFrom());

        entity.setCityCode(req.cityCode().trim().toUpperCase());
        entity.setSocialBase(req.socialBase());
        entity.setHousingFundRate(req.housingFundRate());
        entity.setEffectiveFrom(req.effectiveFrom());
        try {
            socialMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "该员工同生效日已有参保信息");
        }
        auditService.record("EMPLOYEE", entity.getEmployeeId(), "UPDATE", "social_insurance",
                null, "id=" + id + ";from=" + entity.getEffectiveFrom());
        return toDto(entity);
    }

    public List<InsuranceParamDTO> insuranceParams(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            throw new BizException(400, "cityCode 必填");
        }
        List<InsuranceParamEntity> params = paramMapper.findEffective(
                cityCode.trim().toUpperCase(), LocalDate.now());
        if (params.isEmpty()) {
            throw new BizException(400, "该城市没有生效的社保参数：" + cityCode);
        }
        return params.stream().map(p -> new InsuranceParamDTO(
                p.getCityCode(), p.getCityName(), p.getInsuranceType(), p.getPersonalRate(),
                p.getBaseMin(), p.getBaseMax(), p.getExtraAmount(), p.getEffectiveFrom())).toList();
    }

    private void validate(SocialInsuranceSaveReq req) {
        if (req == null || req.cityCode() == null || req.cityCode().isBlank()) {
            throw new BizException(400, "cityCode 必填");
        }
        if (req.effectiveFrom() == null) {
            throw new BizException(400, "effectiveFrom 必填");
        }
        if (paramMapper.findEffective(req.cityCode().trim().toUpperCase(), LocalDate.now()).isEmpty()) {
            throw new BizException(400, "该城市没有生效的社保参数：" + req.cityCode());
        }
        if (req.socialBase() != null && req.socialBase().signum() < 0) {
            throw new BizException(400, "socialBase 不能为负数");
        }
        if (req.housingFundRate() != null
                && (req.housingFundRate().signum() < 0
                || req.housingFundRate().compareTo(BigDecimal.ONE) > 0)) {
            throw new BizException(400, "housingFundRate 必须在 0–1 之间");
        }
    }

    /** 同生效日只允许改未锁定期间：生效日落在已锁定期间内直接 409。 */
    private void assertNotLocked(Long employeeId, LocalDate effectiveFrom) {
        EmpEmployeeEntity employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            return;
        }
        String maxLocked = periodMapper.maxLockedPeriodYm(employee.getCompanyId());
        if (maxLocked != null && !effectiveFrom.isAfter(YearMonth.parse(maxLocked).atEndOfMonth())) {
            throw new BizException(409, "生效日所属期间已锁定，不能修改参保信息");
        }
    }

    private static SocialInsuranceDTO toDto(SocialInsuranceEntity e) {
        return new SocialInsuranceDTO(e.getId(), e.getEmployeeId(), e.getCityCode(),
                e.getSocialBase(), e.getHousingFundRate(), e.getEffectiveFrom(), e.getEffectiveTo());
    }
}
