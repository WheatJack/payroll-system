package com.insbean.payroll.importjob;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.importjob.dto.EmployeeImportRow;
import com.insbean.payroll.importjob.dto.ImportCommitResultDTO;
import com.insbean.payroll.importjob.dto.ImportErrorDTO;
import com.insbean.payroll.importjob.dto.ImportValidateResultDTO;
import com.insbean.payroll.importjob.mapper.ImportBatchMapper;
import com.insbean.payroll.importjob.mapper.ImportErrorMapper;
import com.insbean.payroll.report.PreparedExport;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 人员批量导入：复用批次/错误/文件持久化 + 预校验 + 提交的既有模式。
 *
 * <p>与工资数据导入不同，这里没有期间：批次固定用 {@code period_id = 0} 作为哨兵，
 * 这样唯一键 {@code (period_id, biz_type, file_hash)} 仍然能拦住同一文件重复上传
 * （MySQL 唯一索引不约束 NULL，用 NULL 会失去幂等性）。
 */
@Slf4j
@Service
public class EmployeeImportService {

    static final String BIZ_TYPE = "EMPLOYEE";
    static final long SENTINEL_PERIOD_ID = 0L;
    private static final String STATUS_VALIDATING = "VALIDATING";
    private static final String STATUS_VALIDATED = "VALIDATED";
    private static final String STATUS_COMMITTED = "COMMITTED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneId.systemDefault());

    private final ImportBatchMapper batchMapper;
    private final ImportErrorMapper errorMapper;
    private final EmployeeImportValidator validator;
    private final EmployeeService employeeService;
    private final CompProfileService compProfileService;
    private final AuditService auditService;
    private final DataScopeEnforcer dataScope;
    private final TransactionTemplate transactionTemplate;
    private final Path storageDir;
    private final int maxRows;
    private final int maxErrorRows;

    public EmployeeImportService(ImportBatchMapper batchMapper,
                                 ImportErrorMapper errorMapper,
                                 EmployeeImportValidator validator,
                                 EmployeeService employeeService,
                                 CompProfileService compProfileService,
                                 AuditService auditService,
                                 DataScopeEnforcer dataScope,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${payroll.storage.local-path:./data/import}") String storagePath,
                                 @Value("${payroll.import.max-rows:50000}") int maxRows,
                                 @Value("${payroll.import.max-error-rows:5000}") int maxErrorRows) {
        this.batchMapper = batchMapper;
        this.errorMapper = errorMapper;
        this.validator = validator;
        this.employeeService = employeeService;
        this.compProfileService = compProfileService;
        this.auditService = auditService;
        this.dataScope = dataScope;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.storageDir = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxRows = maxRows;
        this.maxErrorRows = maxErrorRows;
    }

    // ------------------------------------------------------------------ 模板

    public PreparedExport template() {
        Path temp = null;
        try {
            temp = Files.createTempFile("employee-template-", ".xlsx");
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
                 OutputStream out = Files.newOutputStream(temp)) {
                Sheet sheet = workbook.createSheet("人员导入模板");
                CellStyle style = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                style.setFont(font);
                Row header = sheet.createRow(0);
                for (int i = 0; i < EmployeeImportValidator.HEADERS.size(); i++) {
                    var cell = header.createCell(i);
                    cell.setCellValue(EmployeeImportValidator.HEADERS.get(i));
                    cell.setCellStyle(style);
                    sheet.setColumnWidth(i, Math.max(14, EmployeeImportValidator.HEADERS.get(i)
                            .length() * 3) * 256);
                }
                sheet.createFreezePane(0, 1);
                workbook.write(out);
                workbook.dispose();
            }
            return new PreparedExport(temp, "employee-template-" + STAMP.format(Instant.now()) + ".xlsx", 0);
        } catch (IOException e) {
            deleteQuietly(temp);
            log.error("人员导入模板生成失败", e);
            throw new BizException(500, "人员导入模板生成失败");
        }
    }

    // ------------------------------------------------------------------ 预校验

    @Transactional
    public ImportValidateResultDTO validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "上传文件不能为空");
        }
        Long companyId = requireCompany();
        Path temp = null;
        Path stored = null;
        try {
            temp = Files.createTempFile("employee-upload-", ".xlsx");
            file.transferTo(temp.toFile());
            String hash = sha256(temp);
            Files.createDirectories(storageDir);
            stored = storageDir.resolve("employee-" + hash + ".xlsx");
            Files.copy(temp, stored, StandardCopyOption.REPLACE_EXISTING);

            ImportBatchEntity batch = new ImportBatchEntity();
            batch.setPeriodId(SENTINEL_PERIOD_ID);
            batch.setBizType(BIZ_TYPE);
            batch.setFileName(safeFileName(file.getOriginalFilename()));
            batch.setFileHash(hash);
            batch.setFilePath(stored.toString());
            batch.setImportMode("INSERT_ONLY");
            batch.setTotalRows(0);
            batch.setSuccessRows(0);
            batch.setFailRows(0);
            batch.setStatus(STATUS_VALIDATING);
            batch.setOperatorId(requireUser().userId());
            batch.setCreatedAt(LocalDateTime.now());
            try {
                batchMapper.insert(batch);
            } catch (DuplicateKeyException e) {
                throw new BizException(409, "该文件已提交或正在处理，请勿重复导入");
            }
            registerRollbackFileCleanup(stored);

            EmployeeImportValidator.Result result = validator.validate(stored, companyId,
                    maxRows, maxErrorRows);
            if (result.totalRows() == 0) {
                throw new BizException(400, "文件没有数据行");
            }
            persistErrors(batch.getId(), result.errors());
            batch.setTotalRows(result.totalRows());
            batch.setSuccessRows(result.rows().size());
            batch.setFailRows(result.failRows());
            batch.setStatus(STATUS_VALIDATED);
            batch.setFinishedAt(LocalDateTime.now());
            batchMapper.updateById(batch);

            auditService.record("IMPORT", batch.getId(), "CREATE", "employee_validate", null,
                    "file=" + batch.getFileName() + ";total=" + result.totalRows()
                            + ";fail=" + result.failRows());
            return toValidateResult(batch);
        } catch (IOException e) {
            log.error("人员导入文件处理失败", e);
            throw new BizException(500, "人员导入文件处理失败");
        } finally {
            deleteQuietly(temp);
        }
    }

    // ------------------------------------------------------------------ 提交

    public ImportCommitResultDTO commit(Long batchId) {
        ImportBatchEntity batch = requireEmployeeBatch(batchId);
        Long companyId = requireCompany();
        assertBatchOwner(batch);
        if (STATUS_COMMITTED.equals(batch.getStatus())) {
            throw new BizException(409, "该批次已提交，不能重复提交");
        }
        if (!STATUS_VALIDATED.equals(batch.getStatus())) {
            throw new BizException(400, "批次状态不允许提交：" + batch.getStatus());
        }
        if (batch.getFailRows() != null && batch.getFailRows() > 0) {
            throw new ImportConflictException(batchId, batch.getFailRows());
        }

        // 提交时重放 + 重新校验（文件、部门、工号在预览后都可能变化）
        EmployeeImportValidator.Result result = validator.validate(Path.of(batch.getFilePath()),
                companyId, maxRows, maxErrorRows);
        if (result.failRows() > 0) {
            refreshValidationFailure(batchId, result);
            throw new ImportConflictException(batchId, result.failRows());
        }

        try {
            for (int from = 0; from < result.rows().size(); from += BATCH_SIZE) {
                List<EmployeeImportRow> chunk = result.rows().subList(
                        from, Math.min(from + BATCH_SIZE, result.rows().size()));
                transactionTemplate.executeWithoutResult(status -> applyChunk(chunk, companyId));
            }
        } catch (RuntimeException e) {
            markFailed(batchId, e.getMessage());
            throw e;
        }

        ImportBatchEntity update = new ImportBatchEntity();
        update.setId(batchId);
        update.setStatus(STATUS_COMMITTED);
        update.setTotalRows(result.totalRows());
        update.setSuccessRows(result.rows().size());
        update.setFailRows(0);
        update.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(update);
        auditService.record("IMPORT", batchId, "UPDATE", "employee_commit", null,
                "rows=" + result.rows().size());
        return new ImportCommitResultDTO(batchId, STATUS_COMMITTED, result.rows().size(), 0);
    }

    /** 每 500 人一个事务：该批失败整批回滚，不会留下半条人员/档案。 */
    private void applyChunk(List<EmployeeImportRow> chunk, Long companyId) {
        for (EmployeeImportRow row : chunk) {
            Long employeeId = employeeService.create(row.request(), companyId);
            compProfileService.save(new CompProfileSaveReq(null, employeeId,
                    row.request().hireDate(), row.baseSalary(), "批量导入", List.of()));
        }
    }

    private void markFailed(Long batchId, String message) {
        ImportBatchEntity update = new ImportBatchEntity();
        update.setId(batchId);
        update.setStatus(STATUS_FAILED);
        update.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(update);
        log.warn("人员导入提交失败，批次 {} 标记 FAILED：{}", batchId, message);
    }

    private void refreshValidationFailure(Long batchId, EmployeeImportValidator.Result result) {
        transactionTemplate.executeWithoutResult(status -> {
            ImportBatchEntity batch = batchMapper.selectById(batchId);
            if (batch == null) {
                return;
            }
            errorMapper.deleteByBatch(batchId);
            persistErrors(batchId, result.errors());
            batch.setTotalRows(result.totalRows());
            batch.setSuccessRows(result.rows().size());
            batch.setFailRows(result.failRows());
            batch.setStatus(STATUS_VALIDATED);
            batch.setFinishedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
        });
    }

    // ------------------------------------------------------------------ 内部

    private ImportBatchEntity requireEmployeeBatch(Long batchId) {
        ImportBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || !BIZ_TYPE.equals(batch.getBizType())
                || !Long.valueOf(SENTINEL_PERIOD_ID).equals(batch.getPeriodId())) {
            throw new BizException(404, "人员导入批次不存在");
        }
        return batch;
    }

    private void assertBatchOwner(ImportBatchEntity batch) {
        CurrentUser current = requireUser();
        if (DataScopeEnforcer.ALL.equals(current.dataScope())
                || DataScopeEnforcer.COMPANY.equals(current.dataScope())) {
            return;
        }
        if (!current.userId().equals(batch.getOperatorId())) {
            throw new BizException(403, "无权提交他人发起的人员导入批次");
        }
    }

    private Long requireCompany() {
        CurrentUser current = requireUser();
        Long companyId = current.companyId();
        dataScope.assertCompanyVisible(companyId);
        return companyId;
    }

    private void persistErrors(Long batchId, List<ImportValidator.RowError> errors) {
        int limit = Math.min(errors.size(), maxErrorRows);
        for (int i = 0; i < limit; i++) {
            ImportValidator.RowError error = errors.get(i);
            ImportErrorEntity entity = new ImportErrorEntity();
            entity.setBatchId(batchId);
            entity.setRowNo(error.rowNo());
            entity.setColumnName(error.columnName());
            entity.setRawValue(error.rawValue());
            entity.setErrorCode(error.errorCode());
            entity.setErrorMsg(error.errorMsg());
            errorMapper.insert(entity);
        }
    }

    private ImportValidateResultDTO toValidateResult(ImportBatchEntity batch) {
        List<ImportErrorDTO> errors = errorMapper.selectList(
                        Wrappers.<ImportErrorEntity>lambdaQuery()
                                .eq(ImportErrorEntity::getBatchId, batch.getId())
                                .orderByAsc(ImportErrorEntity::getRowNo)
                                .orderByAsc(ImportErrorEntity::getId)
                                .last("LIMIT " + maxErrorRows))
                .stream()
                .map(e -> new ImportErrorDTO(e.getRowNo(), e.getColumnName(), e.getRawValue(),
                        e.getErrorCode(), e.getErrorMsg()))
                .toList();
        return new ImportValidateResultDTO(batch.getId(), batch.getTotalRows(),
                batch.getSuccessRows(), batch.getFailRows(), batch.getStatus(), errors);
    }

    private void registerRollbackFileCleanup(Path stored) {
        long references = batchMapper.selectCount(Wrappers.<ImportBatchEntity>lambdaQuery()
                .eq(ImportBatchEntity::getFilePath, stored.toString()));
        if (references > 1) {
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status
                                != org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) {
                            deleteQuietly(stored);
                            try {
                                Files.deleteIfExists(Path.of(stored.toString()));
                            } catch (IOException ignored) {
                                // 已删除
                            }
                        }
                    }
                });
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "employees.xlsx";
        }
        String base = Path.of(name).getFileName().toString();
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }
}
