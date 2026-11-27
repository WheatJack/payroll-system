package com.insbean.payroll.tax;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.RequirePerm;
import com.insbean.payroll.tax.dto.SpecialDeductionDTO;
import com.insbean.payroll.tax.dto.SpecialDeductionSaveReq;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SpecialDeductionController {

    private final SpecialDeductionService service;

    public SpecialDeductionController(SpecialDeductionService service) {
        this.service = service;
    }

    @RequirePerm("payroll:record:edit")
    @GetMapping("/employees/{id}/special-deductions")
    public Result<List<SpecialDeductionDTO>> list(@PathVariable Long id) {
        return Result.ok(service.list(id));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping("/employees/{id}/special-deductions")
    public Result<SpecialDeductionDTO> save(@PathVariable Long id,
                                            @RequestBody SpecialDeductionSaveReq req) {
        return Result.ok(service.save(id, req));
    }

    @RequirePerm("payroll:record:edit")
    @DeleteMapping("/special-deductions/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
