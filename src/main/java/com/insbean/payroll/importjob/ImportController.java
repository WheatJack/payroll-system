package com.insbean.payroll.importjob;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.importjob.dto.ImportCommitResultDTO;
import com.insbean.payroll.importjob.dto.ImportValidateResultDTO;
import com.insbean.payroll.report.PreparedExport;
import com.insbean.payroll.security.RequirePerm;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** 模板列由工资项字典动态生成（两行表头：名称 + itemCode）。 */
    @RequirePerm("payroll:import:submit")
    @GetMapping("/template")
    public void template(@RequestParam Long periodId,
                         @RequestParam String bizType,
                         HttpServletResponse response) throws IOException {
        if (periodId == null) {
            throw new BizException(400, "periodId 必填");
        }
        writeFile(importService.template(periodId, bizType), response);
    }

    @RequirePerm("payroll:import:submit")
    @PostMapping("/validate")
    public Result<ImportValidateResultDTO> validate(@RequestParam("file") MultipartFile file,
                                                    @RequestParam Long periodId,
                                                    @RequestParam String bizType,
                                                    @RequestParam(required = false) String importMode) {
        if (periodId == null) {
            throw new BizException(400, "periodId 必填");
        }
        return Result.ok(importService.validate(file, periodId, bizType, importMode));
    }

    @RequirePerm("payroll:import:submit")
    @GetMapping("/{batchId}")
    public Result<ImportValidateResultDTO> get(@PathVariable Long batchId) {
        return Result.ok(importService.get(batchId));
    }

    @RequirePerm("payroll:import:submit")
    @PostMapping("/{batchId}/commit")
    public Result<ImportCommitResultDTO> commit(@PathVariable Long batchId) {
        return Result.ok(importService.commit(batchId));
    }

    /** 错误报告含工资金额，与导出同级：要求 payroll:export。 */
    @RequirePerm("payroll:export")
    @GetMapping("/{batchId}/errors")
    public void errors(@PathVariable Long batchId, HttpServletResponse response) throws IOException {
        writeFile(importService.errors(batchId), response);
    }

    /**
     * 提交冲突固定 409，且 data 里必须带 batchId/failRows（契约 ResultImportCommitConflict），
     * 普通 BizException 的 data 恒为 null，所以在这里单独映射。
     */
    @ExceptionHandler(ImportConflictException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleConflict(ImportConflictException e) {
        return ResponseEntity.status(409).body(new Result<>(409, e.getMessage(),
                Map.of("batchId", String.valueOf(e.getBatchId()), "failRows", e.getFailRows())));
    }

    private static void writeFile(PreparedExport prepared, HttpServletResponse response) throws IOException {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(XLSX_CONTENT_TYPE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + prepared.filename() + "\"");
            response.setContentLengthLong(Files.size(prepared.path()));
            try (InputStream in = Files.newInputStream(prepared.path())) {
                in.transferTo(response.getOutputStream());
            }
            response.flushBuffer();
        } finally {
            deleteQuietly(prepared.path());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响响应
        }
    }
}
