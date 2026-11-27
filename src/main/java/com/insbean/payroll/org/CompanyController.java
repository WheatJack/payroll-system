package com.insbean.payroll.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.domain.entity.SysCompanyEntity;
import com.insbean.payroll.org.dto.CompanyDTO;
import com.insbean.payroll.org.mapper.CompanyMapper;
import com.insbean.payroll.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyMapper companyMapper;

    public CompanyController(CompanyMapper companyMapper) {
        this.companyMapper = companyMapper;
    }

    /**
     * 主体列表是公共基础数据：登录即可读，不挂功能权限点。
     *
     * <p>曾误挂 {@code payroll:record:view}，导致纯系统管理员读不到主体列表；
     * 写入由 {@code CompanyAdminController} 的 {@code sys:company:edit} 把关。
     */
    @GetMapping
    public Result<List<CompanyDTO>> list() {
        CurrentUser current = CurrentUser.get();
        var query = Wrappers.<SysCompanyEntity>lambdaQuery()
                .eq(SysCompanyEntity::getStatus, 1)
                .orderByAsc(SysCompanyEntity::getId);
        // 只有集团级（ALL）账号能看到全部主体，其余只能看到自己所属主体
        if (!"ALL".equals(current.dataScope())) {
            query.eq(SysCompanyEntity::getId, current.companyId());
        }
        return Result.ok(companyMapper.selectList(query).stream()
                .map(c -> new CompanyDTO(c.getId(), c.getCompanyCode(), c.getCompanyName(), c.getStatus()))
                .toList());
    }
}
