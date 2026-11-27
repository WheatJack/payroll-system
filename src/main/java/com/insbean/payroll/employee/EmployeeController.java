package com.insbean.payroll.employee;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.employee.dto.EmployeeDTO;
import com.insbean.payroll.employee.dto.EmployeePageDTO;
import com.insbean.payroll.employee.dto.EmployeeQuery;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSensitiveDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.RequirePerm;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping
    public Result<EmployeePageDTO> list(@RequestParam(required = false) Integer current,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) String employeeType,
                                        @RequestParam(required = false) Integer status) {
        EmployeeQuery query = new EmployeeQuery(current, size, keyword, deptId, employeeType, status);
        return Result.ok(employeeService.page(query, CurrentUser.get().companyId()));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping
    public Result<EmployeeDTO> create(@Valid @RequestBody EmployeeSaveReq req) {
        Long id = employeeService.create(req, CurrentUser.get().companyId());
        return Result.ok(employeeService.get(id));
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/{id}")
    public Result<EmployeeDTO> get(@PathVariable Long id) {
        return Result.ok(employeeService.get(id));
    }

    /** 明文只在专用接口返回；需要 payroll:sensitive:view，且每次调用写审计。 */
    @RequirePerm("payroll:sensitive:view")
    @GetMapping("/{id}/sensitive")
    public Result<EmployeeSensitiveDTO> sensitive(@PathVariable Long id) {
        return Result.ok(employeeService.sensitive(id));
    }

    @RequirePerm("payroll:record:edit")
    @PutMapping("/{id}")
    public Result<EmployeeDTO> update(@PathVariable Long id, @Valid @RequestBody EmployeeSaveReq req) {
        return Result.ok(employeeService.update(id, req));
    }
}
