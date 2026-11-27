package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.DeptCreateReq;
import com.insbean.payroll.admin.dto.DeptUpdateReq;
import com.insbean.payroll.admin.mapper.AdminCompanyMapper;
import com.insbean.payroll.admin.mapper.AdminDeptMapper;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.SysCompanyEntity;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 部门的增删改（契约 POST/PUT/DELETE /depts）。
 *
 * <p>ancestors 一律由后端重算：它是数据范围 DEPT_SUB 的唯一定位依据，
 * 让客户端提交等于把越权入口交给前端。父部门变更时还要级联重写全部下级的祖先路径，
 * 否则下级的 DEPT_SUB 过滤会指向旧树。
 */
@Service
public class DeptAdminService {

    private final AdminDeptMapper deptMapper;
    private final AdminCompanyMapper companyMapper;
    private final DataScopeEnforcer dataScope;
    private final AuditService auditService;

    public DeptAdminService(AdminDeptMapper deptMapper, AdminCompanyMapper companyMapper,
                            DataScopeEnforcer dataScope, AuditService auditService) {
        this.deptMapper = deptMapper;
        this.companyMapper = companyMapper;
        this.dataScope = dataScope;
        this.auditService = auditService;
    }

    @Transactional
    public DeptDTO create(DeptCreateReq req) {
        if (req == null || req.companyId() == null) {
            throw new BizException(400, "companyId 必填");
        }
        dataScope.assertCompanyVisible(req.companyId());
        // 主体必须真实存在且启用：否则 ALL 账号能建出挂在不存在主体下的孤儿部门
        requireEnabledCompany(req.companyId());
        requireText(req.deptCode(), "部门编码不能为空");
        requireText(req.deptName(), "部门名称不能为空");
        Long parentId = req.parentId() == null ? 0L : req.parentId();

        SysDeptEntity entity = new SysDeptEntity();
        entity.setCompanyId(req.companyId());
        entity.setParentId(parentId);
        entity.setDeptCode(req.deptCode().trim());
        entity.setDeptName(req.deptName().trim());
        entity.setAncestors(ancestorsOf(req.companyId(), parentId));
        entity.setSortNo(req.sortNo() == null ? 0 : req.sortNo());
        entity.setStatus(req.status() == null ? 1 : req.status());
        entity.setDeleted(0);
        try {
            deptMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "部门编码已存在：" + req.deptCode());
        }
        auditService.record("DEPT", entity.getId(), "CREATE", "deptCode", null, entity.getDeptCode());
        return toDto(entity);
    }

    @Transactional
    public DeptDTO update(Long id, DeptUpdateReq req) {
        SysDeptEntity entity = requireDept(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        requireEnabledCompany(entity.getCompanyId());

        if (req.parentId() != null && !req.parentId().equals(entity.getParentId())) {
            Long parentId = req.parentId();
            if (parentId.equals(id)) {
                throw new BizException(400, "上级部门不能是自己");
            }
            String newAncestors = ancestorsOf(entity.getCompanyId(), parentId);
            SysDeptEntity parent = parentId == 0 ? null : requireDept(parentId);
            if (parent != null && containsDept(parent.getAncestors(), id)) {
                throw new BizException(400, "上级部门不能是自己的下级，会形成环");
            }
            cascadeDescendants(entity, newAncestors);
            entity.setParentId(parentId);
            entity.setAncestors(newAncestors);
        }
        if (req.deptCode() != null) {
            requireText(req.deptCode(), "部门编码不能为空");
            entity.setDeptCode(req.deptCode().trim());
        }
        if (req.deptName() != null) {
            requireText(req.deptName(), "部门名称不能为空");
            entity.setDeptName(req.deptName().trim());
        }
        if (req.sortNo() != null) {
            entity.setSortNo(req.sortNo());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        try {
            deptMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "部门编码已存在：" + req.deptCode());
        }
        auditService.record("DEPT", id, "UPDATE", "parentId", null, String.valueOf(entity.getParentId()));
        return toDto(entity);
    }

    @Transactional
    public void delete(Long id) {
        SysDeptEntity entity = requireDept(id);
        dataScope.assertCompanyVisible(entity.getCompanyId());
        if (deptMapper.countChildren(id) > 0) {
            throw new BizException(409, "存在下级部门，请先迁移或删除下级");
        }
        if (deptMapper.countEmployees(id) > 0) {
            throw new BizException(409, "部门下仍有员工，请先迁移");
        }
        if (deptMapper.countUsers(id) > 0) {
            throw new BizException(409, "部门下仍有系统用户，请先迁移");
        }
        deptMapper.deleteById(id);
        auditService.record("DEPT", id, "DELETE", "deptCode", entity.getDeptCode(), null);
    }

    // ------------------------------------------------------------------ 内部

    /** ancestors = 父的 ancestors + 父 id + ","；根部门为空串。 */
    private String ancestorsOf(Long companyId, Long parentId) {
        if (parentId == null || parentId == 0) {
            return "";
        }
        SysDeptEntity parent = requireDept(parentId);
        if (!parent.getCompanyId().equals(companyId)) {
            throw new BizException(400, "上级部门不属于同一主体");
        }
        return subtreePrefix(parent.getAncestors(), parent.getId());
    }

    /**
     * 下一级节点的祖先路径（也是该节点全部下级的路径前缀）。
     *
     * <p>根部门的 ancestors 是空串，如果不补前导逗号，一级部门的祖先会变成 "4,"，
     * 而 DEPT_SUB 的 {@code ancestors LIKE '%,4,%'} 匹配不上——数据范围会静默少看一个层级。
     */
    private static String subtreePrefix(String ancestors, Long id) {
        if (ancestors == null || ancestors.isEmpty()) {
            return "," + id + ",";
        }
        return ancestors + id + ",";
    }

    /** 父变更后，所有下级的祖先路径要把旧前缀替换成新前缀。 */
    private void cascadeDescendants(SysDeptEntity node, String newAncestors) {
        String oldSelfPrefix = subtreePrefix(node.getAncestors(), node.getId());
        String newSelfPrefix = subtreePrefix(newAncestors, node.getId());
        List<SysDeptEntity> descendants = deptMapper.findDescendants(node.getId());
        for (SysDeptEntity descendant : descendants) {
            String ancestors = descendant.getAncestors();
            String suffix = ancestors != null && ancestors.startsWith(oldSelfPrefix)
                    ? ancestors.substring(oldSelfPrefix.length())
                    : ancestors;
            deptMapper.updateAncestors(descendant.getId(), newSelfPrefix + suffix);
        }
    }

    private static boolean containsDept(String ancestors, Long deptId) {
        return ancestors != null && ancestors.contains("," + deptId + ",");
    }

    private void requireEnabledCompany(Long companyId) {
        SysCompanyEntity company = companyMapper.selectById(companyId);
        if (company == null || company.getStatus() == null || company.getStatus() != 1) {
            throw new BizException(400, "法人主体不存在或已停用：" + companyId);
        }
    }

    private SysDeptEntity requireDept(Long id) {
        SysDeptEntity entity = deptMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "部门不存在");
        }
        return entity;
    }

    private static DeptDTO toDto(SysDeptEntity e) {
        return new DeptDTO(e.getId(), e.getCompanyId(), e.getParentId(), e.getDeptCode(),
                e.getDeptName(), e.getAncestors(), e.getSortNo(), e.getStatus());
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, message);
        }
    }
}
