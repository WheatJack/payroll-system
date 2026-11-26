package com.insbean.payroll.employee;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.EmpEmploymentEntity;
import com.insbean.payroll.employee.dto.EmployeeDTO;
import com.insbean.payroll.employee.dto.EmployeePageDTO;
import com.insbean.payroll.employee.dto.EmployeeQuery;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.employee.mapper.EmploymentMapper;
import com.insbean.payroll.security.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class EmployeeService {

    private final EmployeeMapper employeeMapper;
    private final EmploymentMapper employmentMapper;
    private final CryptoService cryptoService;

    public EmployeeService(EmployeeMapper employeeMapper,
                           EmploymentMapper employmentMapper,
                           CryptoService cryptoService) {
        this.employeeMapper = employeeMapper;
        this.employmentMapper = employmentMapper;
        this.cryptoService = cryptoService;
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

        String hash = cryptoService.blindIndex(req.idNo());
        if (employeeMapper.countByIdNoHash(hash) > 0) {
            throw new BizException(409, "该证件号码已存在人员记录");
        }
        if (employeeMapper.countByEmpNo(req.empNo()) > 0) {
            throw new BizException(409, "工号已被占用：" + req.empNo());
        }

        EmpEmployeeEntity entity = new EmpEmployeeEntity();
        entity.setCompanyId(req.companyId() != null ? req.companyId() : defaultCompanyId);
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

        return entity.getId();
    }

    @Transactional
    public EmployeeDTO update(Long id, EmployeeSaveReq req) {
        EmpEmployeeEntity entity = employeeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "人员不存在");
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
            employmentMapper.updateOpenInterval(entity.getId(), entity.getCompanyId(), entity.getDeptId());
        }
        return toDto(entity);
    }

    public EmployeeDTO get(Long id) {
        EmpEmployeeEntity entity = employeeMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "人员不存在");
        }
        return toDto(entity);
    }

    public EmployeePageDTO page(EmployeeQuery query, Long companyId) {
        var wrapper = Wrappers.<EmpEmployeeEntity>lambdaQuery()
                .eq(EmpEmployeeEntity::getCompanyId, companyId);
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
                page.getRecords().stream().map(EmployeeService::toDto).toList(),
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

    private static EmployeeDTO toDto(EmpEmployeeEntity e) {
        return new EmployeeDTO(
                e.getId(), e.getCompanyId(), e.getEmpNo(), e.getName(), e.getGender(), e.getIdType(),
                e.getIdNoMask(), e.getMobileMask(), e.getEmployeeType(), e.getJobLevel(),
                e.getPositionName(), e.getDeptId(), e.getHireDate(), e.getWorkStartDate(),
                e.getLeaveDate(), e.getStatus(), e.getRemark());
    }
}
