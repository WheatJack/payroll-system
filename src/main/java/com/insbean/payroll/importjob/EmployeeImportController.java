package com.insbean.payroll.importjob;

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

/** 人员批量导入（人员 + 薪酬档案），路径与工资数据导入并列、互不影响。 */
@RestController
@RequestMapping("/api/v1/import")
public class EmployeeImportController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final EmployeeImportService employeeImportService;

    public EmployeeImportController(EmployeeImportService employeeImportService) {
        this.employeeImportService = employeeImportService;
    }

    @RequirePerm("payroll:record:edit")
    @GetMapping("/employee-template")
    public void template(HttpServletResponse response) throws IOException {
        writeFile(employeeImportService.template(), response);
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping("/employees/validate")
    public Result<ImportValidateResultDTO> validate(@RequestParam("file") MultipartFile file) {
        return Result.ok(employeeImportService.validate(file));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping("/employees/{batchId}/commit")
    public Result<ImportCommitResultDTO> commit(@PathVariable Long batchId) {
        return Result.ok(employeeImportService.commit(batchId));
    }

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
