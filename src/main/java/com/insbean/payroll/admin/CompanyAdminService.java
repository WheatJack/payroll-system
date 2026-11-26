package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.CompanyCreateReq;
import com.insbean.payroll.admin.dto.CompanyUpdateReq;
import com.insbean.payroll.admin.mapper.AdminCompanyMapper;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.SysCompanyEntity;
import com.insbean.payroll.org.dto.CompanyDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 法人主体的新增与修改（契约 POST /companies、PUT /companies/{id}）。
 * 列表接口在 {@code org.CompanyController}，属于组织基础数据，不复刻。
 */
@Service
public class CompanyAdminService {

    private final AdminCompanyMapper companyMapper;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;

    public CompanyAdminService(AdminCompanyMapper companyMapper, DataScopeEnforcer dataScope,
                               AuditService auditService) {
        this.companyMapper = companyMapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    @Transactional
    public CompanyDTO create(CompanyCreateReq req) {
        CurrentUser me = requireUser();
        // 新建主体是集团级配置，只有数据范围 ALL 的账号可以操作
        if (!DataScopeEnforcer.ALL.equals(me.dataScope())) {
            throw new BizException(403, "只有集团级账号可以新增法人主体");
        }
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        requireText(req.companyCode(), "主体编码不能为空");
        requireText(req.companyName(), "主体名称不能为空");
        if (companyMapper.countByCode(req.companyCode().trim()) > 0) {
            throw new BizException(409, "主体编码已存在：" + req.companyCode());
        }

        SysCompanyEntity entity = new SysCompanyEntity();
        entity.setCompanyCode(req.companyCode().trim());
        entity.setCompanyName(req.companyName().trim());
        entity.setStatus(req.status() == null ? 1 : req.status());
        entity.setDeleted(0);
        try {
            companyMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "主体编码已存在：" + req.companyCode());
        }
        auditService.record("COMPANY", entity.getId(), "CREATE", "companyCode", null, entity.getCompanyCode());
        return toDto(entity);
    }

    @Transactional
    public CompanyDTO update(Long id, CompanyUpdateReq req) {
        SysCompanyEntity entity = companyMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "法人主体不存在");
        }
        dataScope.assertCompanyVisible(id);
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (req.companyCode() != null) {
            requireText(req.companyCode(), "主体编码不能为空");
            if (!req.companyCode().trim().equals(entity.getCompanyCode())
                    && companyMapper.countByCodeExcluding(req.companyCode().trim(), id) > 0) {
                throw new BizException(409, "主体编码已存在：" + req.companyCode());
            }
            entity.setCompanyCode(req.companyCode().trim());
        }
        if (req.companyName() != null) {
            requireText(req.companyName(), "主体名称不能为空");
            entity.setCompanyName(req.companyName().trim());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        try {
            companyMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "主体编码已存在：" + req.companyCode());
        }
        auditService.record("COMPANY", id, "UPDATE", "companyName", null, entity.getCompanyName());
        return toDto(entity);
    }

    private static CompanyDTO toDto(SysCompanyEntity e) {
        return new CompanyDTO(e.getId(), e.getCompanyCode(), e.getCompanyName(), e.getStatus());
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, message);
        }
    }
}
