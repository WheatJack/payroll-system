package com.insbean.payroll.employee;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.employee.dto.CompProfileDTO;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.security.RequirePerm;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CompProfileController {

    private final CompProfileService compProfileService;

    public CompProfileController(CompProfileService compProfileService) {
        this.compProfileService = compProfileService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/employees/{id}/comp-profiles")
    public Result<List<CompProfileDTO>> timeline(@PathVariable Long id) {
        return Result.ok(compProfileService.timeline(id));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping("/comp-profiles")
    public Result<CompProfileDTO> create(@Valid @RequestBody CompProfileSaveReq req) {
        return Result.ok(compProfileService.create(req));
    }

    @RequirePerm("payroll:record:edit")
    @PutMapping("/comp-profiles/{id}")
    public Result<CompProfileDTO> update(@PathVariable Long id, @Valid @RequestBody CompProfileSaveReq req) {
        return Result.ok(compProfileService.update(id, req));
    }
}
