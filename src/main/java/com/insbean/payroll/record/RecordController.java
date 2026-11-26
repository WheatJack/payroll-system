package com.insbean.payroll.record;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.record.dto.ChangesetResult;
import com.insbean.payroll.record.dto.GridResponse;
import com.insbean.payroll.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/periods/{periodId}")
public class RecordController {

    private final RecordService recordService;

    public RecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping("/grid")
    public Result<GridResponse> grid(@PathVariable Long periodId) {
        return Result.ok(recordService.grid(periodId));
    }

    @RequirePerm("payroll:record:edit")
    @PatchMapping("/records")
    public Result<ChangesetResult> saveChangeset(@PathVariable Long periodId,
                                                 @RequestBody ChangesetReq req) {
        return Result.ok(recordService.applyChangeset(periodId, req));
    }
}
