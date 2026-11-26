package com.insbean.payroll.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.org.mapper.DeptMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeptService {

    private final DeptMapper deptMapper;

    public DeptService(DeptMapper deptMapper) {
        this.deptMapper = deptMapper;
    }

    /**
     * 返回扁平部门列表（不是嵌套结构）：契约要求前端拿 parentId/ancestors 自行组树，
     * 这样前端可以做懒加载与虚拟滚动，后端也不必为每个主体建一次递归。
     */
    public List<DeptDTO> tree(Long companyId) {
        return deptMapper.selectList(Wrappers.<SysDeptEntity>lambdaQuery()
                        .eq(SysDeptEntity::getCompanyId, companyId)
                        .eq(SysDeptEntity::getStatus, 1)
                        .orderByAsc(SysDeptEntity::getSortNo)
                        .orderByAsc(SysDeptEntity::getId))
                .stream()
                .map(d -> new DeptDTO(d.getId(), d.getCompanyId(), d.getParentId(), d.getDeptCode(),
                        d.getDeptName(), d.getAncestors(), d.getSortNo(), d.getStatus()))
                .toList();
    }
}
