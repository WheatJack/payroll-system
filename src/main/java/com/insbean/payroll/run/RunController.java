package com.insbean.payroll.run;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.run.dto.GenerateResultDTO;
import com.insbean.payroll.run.dto.RunProgressDTO;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RunController {

    private final GenerateService generateService;

    public RunController(GenerateService generateService) {
        this.generateService = generateService;
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping("/periods/{id}/generate")
    public Result<GenerateResultDTO> generate(@PathVariable Long id) {
        Long runId = generateService.startGenerate(id, CurrentUser.get().userId());
        return Result.ok(new GenerateResultDTO(runId));
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/runs/{runId}")
    public Result<RunProgressDTO> progress(@PathVariable Long runId) {
        return Result.ok(generateService.progress(runId));
    }
}
