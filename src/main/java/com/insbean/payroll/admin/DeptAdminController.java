package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.DeptCreateReq;
import com.insbean.payroll.admin.dto.DeptUpdateReq;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只补齐写接口；GET /depts 在 org.DeptController（组织基础数据）。 */
@RestController
@RequestMapping("/api/v1/depts")
public class DeptAdminController {

    private final DeptAdminService deptAdminService;

    public DeptAdminController(DeptAdminService deptAdminService) {
        this.deptAdminService = deptAdminService;
    }

    @RequirePerm("sys:dept:edit")
    @PostMapping
    public Result<DeptDTO> create(@RequestBody DeptCreateReq req) {
        return Result.ok(deptAdminService.create(req));
    }

    @RequirePerm("sys:dept:edit")
    @PutMapping("/{id}")
    public Result<DeptDTO> update(@PathVariable Long id, @RequestBody DeptUpdateReq req) {
        return Result.ok(deptAdminService.update(id, req));
    }

    @RequirePerm("sys:dept:edit")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        deptAdminService.delete(id);
        return Result.ok();
    }
}
