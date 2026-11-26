package com.insbean.payroll.org;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.org.dto.DeptDTO;
import com.insbean.payroll.security.RequirePerm;
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

    @RequirePerm("payroll:record:view")
    @GetMapping
    public Result<List<DeptDTO>> list(@RequestParam Long companyId,
                                      @RequestParam(defaultValue = "false") boolean includeDisabled) {
        if (companyId == null) {
            throw new BizException(400, "companyId 必填");
        }
        return Result.ok(deptService.tree(companyId, includeDisabled));
    }
}
