package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.PermissionItemDTO;
import com.insbean.payroll.admin.dto.RoleCreateReq;
import com.insbean.payroll.admin.dto.RoleDTO;
import com.insbean.payroll.admin.dto.RoleGrantReq;
import com.insbean.payroll.admin.dto.RoleUpdateReq;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RoleAdminController {

    private final RoleAdminService roleAdminService;

    public RoleAdminController(RoleAdminService roleAdminService) {
        this.roleAdminService = roleAdminService;
    }

    /** 权限点目录：前端角色授权页从这里渲染勾选项，避免硬编码。 */
    @RequirePerm("sys:role:view")
    @GetMapping("/permissions")
    public Result<List<PermissionItemDTO>> permissions() {
        return Result.ok(PermissionCatalog.all());
    }

    @RequirePerm("sys:role:view")
    @GetMapping("/roles")
    public Result<List<RoleDTO>> list(@RequestParam(required = false) Long companyId,
                                      @RequestParam(required = false) Integer status) {
        return Result.ok(roleAdminService.list(companyId, status));
    }

    @RequirePerm("sys:role:edit")
    @PostMapping("/roles")
    public Result<RoleDTO> create(@RequestBody RoleCreateReq req) {
        return Result.ok(roleAdminService.create(req));
    }

    @RequirePerm("sys:role:edit")
    @PutMapping("/roles/{id}")
    public Result<RoleDTO> update(@PathVariable Long id, @RequestBody RoleUpdateReq req) {
        return Result.ok(roleAdminService.update(id, req));
    }

    @RequirePerm("sys:role:edit")
    @DeleteMapping("/roles/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleAdminService.delete(id);
        return Result.ok();
    }

    @RequirePerm("sys:role:edit")
    @PutMapping("/roles/{id}/grants")
    public Result<RoleDTO> replaceGrants(@PathVariable Long id, @RequestBody RoleGrantReq req) {
        return Result.ok(roleAdminService.replaceGrants(id, req));
    }
}
