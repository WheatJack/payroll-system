package com.insbean.payroll.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import com.insbean.payroll.security.mapper.AuthMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeptService {

    private final DeptMapper deptMapper;
    private final AuthMapper authMapper;
    private final DataScopeEnforcer dataScope;

    public DeptService(DeptMapper deptMapper, AuthMapper authMapper, DataScopeEnforcer dataScope) {
        this.deptMapper = deptMapper;
        this.authMapper = authMapper;
        this.dataScope = dataScope;
    }

    /**
     * 返回扁平部门列表（不是嵌套结构）：契约要求前端拿 parentId/ancestors 自行组树，
     * 这样前端可以做懒加载与虚拟滚动，后端也不必为每个主体建一次递归。
     *
     * @param includeDisabled 契约 v1.1：默认 false 只返回启用部门；
     *                        系统管理页需要 true 才能显示并重新启用已停用部门
     */
    public List<DeptDTO> tree(Long companyId, boolean includeDisabled) {
        dataScope.assertCompanyVisible(companyId);
        var query = Wrappers.<SysDeptEntity>lambdaQuery()
                .eq(SysDeptEntity::getCompanyId, companyId)
                .orderByAsc(SysDeptEntity::getSortNo)
                .orderByAsc(SysDeptEntity::getId);
        if (!includeDisabled) {
            query.eq(SysDeptEntity::getStatus, 1);
        }
        // 部门级账号只返回自己可见的部门：主管不该在下拉里看到一个选了就返回空的部门
        CurrentUser current = CurrentUser.get();
        if (current != null && !DataScopeEnforcer.ALL.equals(current.dataScope())) {
            Long myDept = authMapper.findDeptId(current.userId());
            if (DataScopeEnforcer.DEPT.equals(current.dataScope()) && myDept != null) {
                query.eq(SysDeptEntity::getId, myDept);
            } else if (DataScopeEnforcer.DEPT_SUB.equals(current.dataScope()) && myDept != null) {
                query.in(SysDeptEntity::getId, deptMapper.findSubtreeIds(myDept));
            }
        }
        return deptMapper.selectList(query)
                .stream()
                .map(d -> new DeptDTO(d.getId(), d.getCompanyId(), d.getParentId(), d.getDeptCode(),
                        d.getDeptName(), d.getAncestors(), d.getSortNo(), d.getStatus()))
                .toList();
    }

    /** 兼容旧调用方：默认不含停用部门。 */
    public List<DeptDTO> tree(Long companyId) {
        return tree(companyId, false);
    }
}
