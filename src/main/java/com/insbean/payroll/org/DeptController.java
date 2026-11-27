package com.insbean.payroll.org;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.org.dto.DeptDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/depts")
public class DeptController {

    private final DeptService deptService;

    public DeptController(DeptService deptService) {
        this.deptService = deptService;
    }

    /**
     * 组织树是公共基础数据：登录即可读，不挂功能权限点。
     *
     * <p>曾误挂 {@code payroll:record:view}，导致纯系统管理员（只有 sys:*）能改部门却读不到部门，
     * 而薪酬专员反而能读组织结构；与契约「已认证用户可读」不符。写入仍由
     * {@code DeptAdminController} 的 {@code sys:dept:edit} 把关。
     */
    @GetMapping
    public Result<List<DeptDTO>> list(@RequestParam Long companyId,
                                      @RequestParam(defaultValue = "false") boolean includeDisabled) {
        if (companyId == null) {
            throw new BizException(400, "companyId 必填");
        }
        return Result.ok(deptService.tree(companyId, includeDisabled));
    }
}
