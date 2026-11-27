package com.insbean.payroll.tax;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.RequirePerm;
import com.insbean.payroll.tax.dto.InsuranceParamDTO;
import com.insbean.payroll.tax.dto.SocialInsuranceDTO;
import com.insbean.payroll.tax.dto.SocialInsuranceSaveReq;
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
public class SocialInsuranceController {

    private final SocialInsuranceService service;

    public SocialInsuranceController(SocialInsuranceService service) {
        this.service = service;
    }

    @RequirePerm("payroll:record:edit")
    @GetMapping("/employees/{id}/social-insurance")
    public Result<List<SocialInsuranceDTO>> timeline(@PathVariable Long id) {
        return Result.ok(service.timeline(id));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping("/employees/{id}/social-insurance")
    public Result<SocialInsuranceDTO> create(@PathVariable Long id,
                                             @RequestBody SocialInsuranceSaveReq req) {
        return Result.ok(service.create(id, req));
    }

    @RequirePerm("payroll:record:edit")
    @PutMapping("/social-insurance/{id}")
    public Result<SocialInsuranceDTO> update(@PathVariable Long id,
                                             @RequestBody SocialInsuranceSaveReq req) {
        return Result.ok(service.update(id, req));
    }

    @RequirePerm("payroll:record:edit")
    @GetMapping("/insurance-params")
    public Result<List<InsuranceParamDTO>> params(@RequestParam String cityCode) {
        return Result.ok(service.insuranceParams(cityCode));
    }
}
