package com.insbean.payroll.employee;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.EmpEmploymentEntity;
import com.insbean.payroll.employee.dto.EmployeeDTO;
import com.insbean.payroll.employee.dto.EmployeePageDTO;
import com.insbean.payroll.employee.dto.EmployeeQuery;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.employee.mapper.EmploymentMapper;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.security.CryptoService;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

@Service
public class EmployeeService {

    private final EmployeeMapper employeeMapper;
    private final EmploymentMapper employmentMapper;
    private final CryptoService cryptoService;
    private final DeptMapper deptMapper;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;

    public EmployeeService(EmployeeMapper employeeMapper,
                           EmploymentMapper employmentMapper,
                           CryptoService cryptoService,
                           DeptMapper deptMapper,
                           DataScopeEnforcer dataScope,
                           AuditService auditService) {
        this.employeeMapper = employeeMapper;
        this.employmentMapper = employmentMapper;
        this.cryptoService = cryptoService;
        this.deptMapper = deptMapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    @Transactional
    public Long create(EmployeeSaveReq req, Long defaultCompanyId) {
        requireText(req.empNo(), "工号");
        requireText(req.name(), "姓名");
        requireText(req.idNo(), "证件号");
        requireText(req.employeeType(), "用工类型");
        if (req.deptId() == null) {
            throw new BizException(400, "部门必填");
        }
        if (req.hireDate() == null) {
            throw new BizException(400, "入职日期必填");
        }
        Long companyId = req.companyId() != null ? req.companyId() : defaultCompanyId;
        dataScope.assertCompanyVisible(companyId);

        String hash = cryptoService.blindIndex(req.idNo());
        if (employeeMapper.countByIdNoHash(hash) > 0) {
            throw new BizException(409, "该证件号码已存在人员记录");
        }
        if (employeeMapper.countByEmpNo(req.empNo()) > 0) {
            throw new BizException(409, "工号已被占用：" + req.empNo());
        }

        EmpEmployeeEntity entity = new EmpEmployeeEntity();
        entity.setCompanyId(companyId);
        entity.setEmpNo(req.empNo());
        entity.setName(req.name());
        entity.setGender(req.gender() == null ? 0 : req.gender());
        entity.setIdType(req.idType() == null ? "ID_CARD" : req.idType());
        entity.setIdNoCipher(cryptoService.encrypt(req.idNo()));
        entity.setIdNoHash(hash);
        entity.setIdNoHashVersion(1);
        entity.setIdNoMask(CryptoService.maskIdNo(req.idNo()));
        entity.setMobileCipher(cryptoService.encrypt(req.mobile()));
        entity.setMobileHash(cryptoService.blindIndex(req.mobile()));
        entity.setMobileMask(CryptoService.maskMobile(req.mobile()));
        entity.setEmployeeType(req.employeeType());
        entity.setJobLevel(req.jobLevel());
        entity.setPositionName(req.positionName());
        entity.setDeptId(req.deptId());
        entity.setHireDate(req.hireDate());
        entity.setWorkStartDate(req.workStartDate());
        entity.setLeaveDate(req.leaveDate());
        entity.setStatus(req.status() == null ? 1 : req.status());
        entity.setRemark(req.remark());
        entity.setDeleted(0);
        employeeMapper.insert(entity);

        // 任职区间是核算时判定"这个人算在哪家主体"的唯一依据（详细设计 §4.9）
        EmpEmploymentEntity employment = new EmpEmploymentEntity();
        employment.setEmployeeId(entity.getId());
        employment.setCompanyId(entity.getCompanyId());
        employment.setDeptId(entity.getDeptId());
        employment.setStartDate(entity.getHireDate());
        employment.setChangeType("HIRE");
        employment.setDeleted(0);
        employmentMapper.insert(employment);

        auditService.record("EMPLOYEE", entity.getId(), "CREATE", "empNo", null, entity.getEmpNo());
        return entity.getId();
    }

    @Transactional
    public EmployeeDTO update(Long id, EmployeeSaveReq req) {
        dataScope.assertEmployeeVisible(id);
        EmpEmployeeEntity entity = employeeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "人员不存在");
        }
        if (req.companyId() != null) {
            dataScope.assertCompanyVisible(req.companyId());
        }
        if (req.empNo() != null && !req.empNo().equals(entity.getEmpNo())) {
            if (employeeMapper.countByEmpNoExcluding(req.empNo(), id) > 0) {
                throw new BizException(409, "工号已被占用：" + req.empNo());
            }
            entity.setEmpNo(req.empNo());
        }
        if (req.idNo() != null && !req.idNo().isBlank()) {
            String hash = cryptoService.blindIndex(req.idNo());
            if (!hash.equals(entity.getIdNoHash())) {
                if (employeeMapper.countByIdNoHashExcluding(hash, id) > 0) {
                    throw new BizException(409, "该证件号码已存在人员记录");
                }
                entity.setIdNoCipher(cryptoService.encrypt(req.idNo()));
                entity.setIdNoHash(hash);
                entity.setIdNoMask(CryptoService.maskIdNo(req.idNo()));
            }
        }
        if (req.mobile() != null && !req.mobile().isBlank()) {
            entity.setMobileCipher(cryptoService.encrypt(req.mobile()));
            entity.setMobileHash(cryptoService.blindIndex(req.mobile()));
            entity.setMobileMask(CryptoService.maskMobile(req.mobile()));
        }

        Long previousCompany = entity.getCompanyId();
        Long previousDept = entity.getDeptId();
        if (req.companyId() != null) {
            entity.setCompanyId(req.companyId());
        }
        if (req.name() != null) {
            entity.setName(req.name());
        }
        if (req.gender() != null) {
            entity.setGender(req.gender());
        }
        if (req.idType() != null) {
            entity.setIdType(req.idType());
        }
        if (req.employeeType() != null) {
            entity.setEmployeeType(req.employeeType());
        }
        if (req.jobLevel() != null) {
            entity.setJobLevel(req.jobLevel());
        }
        if (req.positionName() != null) {
            entity.setPositionName(req.positionName());
        }
        if (req.deptId() != null) {
            entity.setDeptId(req.deptId());
        }
        if (req.hireDate() != null) {
            entity.setHireDate(req.hireDate());
        }
        if (req.workStartDate() != null) {
            entity.setWorkStartDate(req.workStartDate());
        }
        if (req.leaveDate() != null) {
            entity.setLeaveDate(req.leaveDate());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        if (req.remark() != null) {
            entity.setRemark(req.remark());
        }
        employeeMapper.updateById(entity);

        if (!Objects.equals(previousCompany, entity.getCompanyId())
                || !Objects.equals(previousDept, entity.getDeptId())) {
            // 调动：关闭旧区间 + 追加新区间（R12）。绝不就地改写既有行。
            LocalDate effectiveDate = req.effectiveDate() != null ? req.effectiveDate() : LocalDate.now();
            transfer(entity.getId(), entity.getCompanyId(), entity.getDeptId(), effectiveDate);
        }
        // 不落证件号/手机号明文；只记变更摘要
        auditService.record("EMPLOYEE", entity.getId(), "UPDATE", "profile", null,
                "empNo=" + entity.getEmpNo() + ";deptId=" + entity.getDeptId()
                        + ";status=" + entity.getStatus());
        return toDto(entity);
    }

    /**
     * 员工调动到另一个主体/部门。
     *
     * <p>M1 不做「调动单」（那是二期），但必须保证历史归属可追溯：
     * 旧区间在 {@code effectiveDate - 1} 关闭，新区间从 {@code effectiveDate} 起生效，
     * {@code change_type = TRANSFER}。这样解锁重算历史期间时，取到的仍是当时的主体。
     *
     * <p>「当天入职当天调部门」：新区间不能比旧区间还早，此时旧区间以
     * {@code end_date = effectiveDate} 关闭（当天双区间并存），查归属时按
     * {@code start_date DESC} 取最新的那条，于是当天归属新部门，之前归属旧部门。
     *
     * @param effectiveDate 调动生效日（含），不得早于当前任职区间的起始日
     */
    @Transactional
    public void transfer(Long employeeId, Long companyId, Long deptId, LocalDate effectiveDate) {
        if (effectiveDate == null) {
            throw new BizException(400, "调动生效日必填");
        }
        dataScope.assertEmployeeVisible(employeeId);
        dataScope.assertCompanyVisible(companyId);
        requireEmployee(employeeId);

        EmpEmploymentEntity open = employmentMapper.selectOne(
                Wrappers.<EmpEmploymentEntity>lambdaQuery()
                        .eq(EmpEmploymentEntity::getEmployeeId, employeeId)
                        .isNull(EmpEmploymentEntity::getEndDate)
                        .orderByDesc(EmpEmploymentEntity::getStartDate)
                        .last("LIMIT 1"));
        if (open != null) {
            if (effectiveDate.isBefore(open.getStartDate())) {
                throw new BizException(400, "调动生效日不能早于当前任职区间的起始日");
            }
            LocalDate closeAt = effectiveDate.equals(open.getStartDate())
                    ? effectiveDate
                    : effectiveDate.minusDays(1);
            employmentMapper.closeInterval(open.getId(), closeAt);
        }

        EmpEmploymentEntity entity = new EmpEmploymentEntity();
        entity.setEmployeeId(employeeId);
        entity.setCompanyId(companyId);
        entity.setDeptId(deptId);
        entity.setStartDate(effectiveDate);
        entity.setChangeType("TRANSFER");
        entity.setDeleted(0);
        employmentMapper.insert(entity);
        auditService.record("EMPLOYEE", employeeId, "UPDATE", "employment", null,
                "companyId=" + companyId + ";deptId=" + deptId + ";effectiveDate=" + effectiveDate);
    }

    /**
     * 某员工在指定日期归属的法人主体。
     * 没有任职区间数据的老人员回落到人员表当前归属，避免历史数据全员漏算。
     */
    public Long companyAt(Long employeeId, LocalDate date) {
        Long companyId = employmentMapper.findCompanyAt(employeeId, date);
        return companyId != null ? companyId : requireEmployee(employeeId).getCompanyId();
    }

    public EmployeeDTO get(Long id) {
        dataScope.assertEmployeeVisible(id);
        EmpEmployeeEntity entity = employeeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "人员不存在");
        }
        return toDto(entity);
    }

    public EmployeePageDTO page(EmployeeQuery query, Long companyId) {
        dataScope.assertCompanyVisible(companyId);
        var wrapper = Wrappers.<EmpEmployeeEntity>lambdaQuery()
                .eq(EmpEmployeeEntity::getCompanyId, companyId);
        dataScope.applyEmployeeFilter(wrapper);
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String keyword = query.keyword().trim();
            wrapper.and(w -> w.like(EmpEmployeeEntity::getEmpNo, keyword)
                    .or().like(EmpEmployeeEntity::getName, keyword));
        }
        if (query.deptId() != null) {
            wrapper.eq(EmpEmployeeEntity::getDeptId, query.deptId());
        }
        if (query.employeeType() != null && !query.employeeType().isBlank()) {
            wrapper.eq(EmpEmployeeEntity::getEmployeeType, query.employeeType());
        }
        if (query.status() != null) {
            wrapper.eq(EmpEmployeeEntity::getStatus, query.status());
        }
        wrapper.orderByAsc(EmpEmployeeEntity::getEmpNo);

        Page<EmpEmployeeEntity> page = employeeMapper.selectPage(
                new Page<>(query.pageNo(), query.pageSize()), wrapper);
        return new EmployeePageDTO(
                page.getRecords().stream().map(this::toDto).toList(),
                page.getTotal(),
                (int) page.getCurrent(),
                (int) page.getSize());
    }

    public EmpEmployeeEntity requireEmployee(Long id) {
        EmpEmployeeEntity entity = employeeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "人员不存在");
        }
        return entity;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, label + "必填");
        }
    }

    private EmployeeDTO toDto(EmpEmployeeEntity e) {
        LocalDate today = LocalDate.now();
        String deptName = e.getDeptId() == null ? null : deptMapper.findNameById(e.getDeptId());
        return new EmployeeDTO(
                e.getId(), e.getCompanyId(), e.getEmpNo(), e.getName(), e.getGender(), e.getIdType(),
                e.getIdNoMask(), e.getMobileMask(), e.getEmployeeType(), e.getJobLevel(),
                e.getPositionName(), e.getDeptId(), deptName == null ? "" : deptName,
                e.getHireDate(), e.getWorkStartDate(),
                e.getLeaveDate(), wholeYears(e.getHireDate(), today),
                wholeYears(e.getWorkStartDate(), today), e.getStatus(), e.getRemark());
    }

    /** 整年差（向下取整）；入职/参加工作日期为空返回 null，未来日期按 0 处理。 */
    private static Integer wholeYears(LocalDate from, LocalDate to) {
        if (from == null) {
            return null;
        }
        if (from.isAfter(to)) {
            return 0;
        }
        return Period.between(from, to).getYears();
    }
}
