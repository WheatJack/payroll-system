package com.insbean.payroll.period;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.period.dto.PeriodDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/periods")
public class PeriodController {

    private final PeriodService periodService;

    public PeriodController(PeriodService periodService) {
        this.periodService = periodService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping
    public Result<List<PeriodDTO>> list(@RequestParam(required = false) Long companyId) {
        CurrentUser current = CurrentUser.get();
        return Result.ok(periodService.list(companyId != null ? companyId : current.companyId()));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping
    public Result<PeriodDTO> create(@RequestBody PeriodCreateReq req) {
        return Result.ok(periodService.create(req, CurrentUser.get().companyId()));
    }

    @RequirePerm("payroll:period:lock")
    @PostMapping("/{id}/lock")
    public Result<PeriodDTO> lock(@PathVariable Long id) {
        return Result.ok(periodService.lock(id, CurrentUser.get().userId()));
    }

    @RequirePerm("payroll:period:unlock")
    @PostMapping("/{id}/unlock")
    public Result<PeriodDTO> unlock(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        return Result.ok(periodService.unlock(id, CurrentUser.get().userId(),
                reason == null ? null : reason.toString()));
    }
}
