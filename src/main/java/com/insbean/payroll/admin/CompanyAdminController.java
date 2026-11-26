package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.CompanyCreateReq;
import com.insbean.payroll.admin.dto.CompanyUpdateReq;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.org.dto.CompanyDTO;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只补齐写接口；GET /companies 在 org.CompanyController（组织基础数据）。 */
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyAdminController {

    private final CompanyAdminService companyAdminService;

    public CompanyAdminController(CompanyAdminService companyAdminService) {
        this.companyAdminService = companyAdminService;
    }

    @RequirePerm("sys:company:edit")
    @PostMapping
    public Result<CompanyDTO> create(@RequestBody CompanyCreateReq req) {
        return Result.ok(companyAdminService.create(req));
    }

    @RequirePerm("sys:company:edit")
    @PutMapping("/{id}")
    public Result<CompanyDTO> update(@PathVariable Long id, @RequestBody CompanyUpdateReq req) {
        return Result.ok(companyAdminService.update(id, req));
    }
}
