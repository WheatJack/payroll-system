package com.insbean.payroll.admin;

import com.insbean.payroll.admin.dto.ResetPasswordReq;
import com.insbean.payroll.admin.dto.UserCreateReq;
import com.insbean.payroll.admin.dto.UserDTO;
import com.insbean.payroll.admin.dto.UserPageDTO;
import com.insbean.payroll.admin.dto.UserUpdateReq;
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

@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @RequirePerm("sys:user:view")
    @GetMapping
    public Result<UserPageDTO> list(@RequestParam(defaultValue = "1") long current,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long companyId,
                                    @RequestParam(required = false) Long deptId,
                                    @RequestParam(required = false) Integer status) {
        if (current < 1 || size < 1) {
            throw new com.insbean.payroll.common.BizException(400, "分页参数不合法");
        }
        return Result.ok(userAdminService.list(current, size, keyword, companyId, deptId, status));
    }

    @RequirePerm("sys:user:edit")
    @PostMapping
    public Result<UserDTO> create(@RequestBody UserCreateReq req) {
        return Result.ok(userAdminService.create(req));
    }

    @RequirePerm("sys:user:edit")
    @PutMapping("/{id}")
    public Result<UserDTO> update(@PathVariable Long id, @RequestBody UserUpdateReq req) {
        return Result.ok(userAdminService.update(id, req));
    }

    @RequirePerm("sys:user:edit")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userAdminService.delete(id);
        return Result.ok();
    }

    @RequirePerm("sys:user:edit")
    @PostMapping("/{id}/reset-password")
    public Result<UserDTO> resetPassword(@PathVariable Long id,
                                         @RequestBody ResetPasswordReq req) {
        return Result.ok(userAdminService.resetPassword(id, req));
    }
}
