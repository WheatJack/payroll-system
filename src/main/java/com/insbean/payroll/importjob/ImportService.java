package com.insbean.payroll.importjob;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.importjob.dto.ImportCommitResultDTO;
import com.insbean.payroll.importjob.dto.ImportErrorDTO;
import com.insbean.payroll.importjob.dto.ImportValidateResultDTO;
import com.insbean.payroll.importjob.mapper.ImportBatchMapper;
import com.insbean.payroll.importjob.mapper.ImportErrorMapper;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.record.PeriodTotalsRefresher;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.report.ExcelWriter;
import com.insbean.payroll.report.PreparedExport;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
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
 * 变动数据导入（契约 /import/*）。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>预校验不写核算数据</b>，但批次、错误与原始文件必须持久化，否则刷新后无从回捞；</li>
 *   <li><b>提交时重放并重新校验</b>：预览到提交之间期间可能被锁定、记录可能被改；</li>
 *   <li><b>failRows &gt; 0 禁止提交</b>（409 + data.failRows），不能只靠前端置灰；</li>
 *   <li><b>只改已有核算记录，不创建记录</b>：导入是"补变动项"，不是"发工资"。</li>
 * </ul>
 */
@Slf4j
@Service
public class ImportService {

    private static final String STATUS_VALIDATING = "VALIDATING";
    private static final String STATUS_VALIDATED = "VALIDATED";
    private static final String STATUS_COMMITTED = "COMMITTED";
    private static final String SOURCE_IMPORT = "IMPORT";
    private static final String MODE_UPSERT = "UPSERT";
    private static final String MODE_APPEND = "APPEND";
    private static final String MODE_INSERT_ONLY = "INSERT_ONLY";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneId.systemDefault());

    private final ImportBatchMapper batchMapper;
    private final ImportErrorMapper errorMapper;
    private final ImportValidator validator;
    private final PeriodService periodService;
    private final ItemService itemService;
    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final PeriodTotalsRefresher totalsRefresher;
    private final AuditService auditService;
    private final DataScopeEnforcer dataScope;
    private final ExcelWriter excelWriter;
    private final Path storageDir;
    private final int maxRows;
    private final int maxErrorRows;

    public ImportService(ImportBatchMapper batchMapper,
                         ImportErrorMapper errorMapper,
                         ImportValidator validator,
                         PeriodService periodService,
                         ItemService itemService,
                         PayRecordMapper recordMapper,
                         PayRecordItemMapper recordItemMapper,
                         PeriodTotalsRefresher totalsRefresher,
                         AuditService auditService,
                         DataScopeEnforcer dataScope,
                         ExcelWriter excelWriter,
                         @Value("${payroll.storage.local-path:./data/import}") String storagePath,
                         @Value("${payroll.import.max-rows:50000}") int maxRows,
                         @Value("${payroll.import.max-error-rows:5000}") int maxErrorRows) {
        this.batchMapper = batchMapper;
        this.errorMapper = errorMapper;
        this.validator = validator;
        this.periodService = periodService;
        this.itemService = itemService;
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.totalsRefresher = totalsRefresher;
        this.auditService = auditService;
        this.dataScope = dataScope;
        this.excelWriter = excelWriter;
        this.storageDir = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxRows = maxRows;
        this.maxErrorRows = maxErrorRows;
    }

    // ------------------------------------------------------------------ 模板

    public PreparedExport template(Long periodId, String bizType) {
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = periodService.requirePeriod(periodId);
        String category = ImportValidator.categoryOf(bizType);

        List<PayItemDefDTO> items = itemService.list(period.getCompanyId(), category, 1).stream()
                .filter(i -> Boolean.TRUE.equals(i.importable()))
                .toList();

        Path temp = null;
        try {
            temp = Files.createTempFile("import-template-", ".xlsx");
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
                 OutputStream out = Files.newOutputStream(temp)) {
                Sheet sheet = workbook.createSheet("导入模板");
                CellStyle titleStyle = boldStyle(workbook);
                CellStyle keyStyle = keyStyle(workbook);

                Row titles = sheet.createRow(0);
                Row keys = sheet.createRow(1);
                int col = 0;
                col = header(titles, keys, col, "工号", "empNo", titleStyle, keyStyle);
                col = header(titles, keys, col, "姓名", "empName", titleStyle, keyStyle);
                for (PayItemDefDTO item : items) {
                    col = header(titles, keys, col, item.itemName(), item.itemCode(), titleStyle, keyStyle);
                }
                sheet.createFreezePane(0, 2);
                workbook.write(out);
                workbook.dispose();
            }
            String filename = "import-template-" + category + "-" + period.getPeriodYm() + ".xlsx";
            return new PreparedExport(temp, filename, 0);
        } catch (IOException e) {
            deleteQuietly(temp);
            log.error("导入模板生成失败", e);
            throw new BizException(500, "导入模板生成失败");
        }
    }

    // ------------------------------------------------------------------ 预校验

    @Transactional
    public ImportValidateResultDTO validate(MultipartFile file, Long periodId,
                                            String bizType, String importMode) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "上传文件不能为空");
        }
        dataScope.assertPeriodVisible(periodId);
        PayPeriodEntity period = periodService.requirePeriod(periodId);
        // 已锁定期间不可能导入，提前失败比让用户等到提交才发现好
        periodService.assertWritable(periodId);
        String category = ImportValidator.categoryOf(bizType);
        String mode = normalizeMode(importMode);

        Path temp = null;
        Path stored = null;
        try {
            temp = Files.createTempFile("import-upload-", ".xlsx");
            file.transferTo(temp.toFile());
            String hash = sha256(temp);

            Long duplicate = batchMapper.findDuplicate(periodId, hash);
            if (duplicate != null) {
                throw new BizException(409, "该文件已上传过（批次 " + duplicate + "），不能重复导入");
            }

            Files.createDirectories(storageDir);
            stored = storageDir.resolve(hash + ".xlsx");
            Files.copy(temp, stored, StandardCopyOption.REPLACE_EXISTING);

            ImportValidator.Result result = validator.validate(stored, periodId, period.getCompanyId(),
                    category, maxRows, maxErrorRows);
            if (result.totalRows() == 0) {
                throw new BizException(400, "文件没有数据行");
            }

            ImportBatchEntity batch = new ImportBatchEntity();
            batch.setPeriodId(periodId);
            batch.setBizType(category);
            batch.setFileName(safeFileName(file.getOriginalFilename()));
            batch.setFileHash(hash);
            batch.setFilePath(stored.toString());
            batch.setImportMode(mode);
            batch.setTotalRows(result.totalRows());
            batch.setSuccessRows(result.rows().size());
            batch.setFailRows(result.failRows());
            batch.setStatus(STATUS_VALIDATING);
            batch.setOperatorId(requireUser().userId());
            batch.setCreatedAt(LocalDateTime.now());
            batchMapper.insert(batch);

            persistErrors(batch.getId(), result.errors());
            batch.setStatus(STATUS_VALIDATED);
            batch.setFinishedAt(LocalDateTime.now());
            batchMapper.updateById(batch);

            auditService.record("IMPORT", batch.getId(), "CREATE", "validate", null,
                    "file=" + batch.getFileName() + ";total=" + result.totalRows()
                            + ";fail=" + result.failRows());
            return toValidateResult(batch);
        } catch (IOException e) {
            log.error("导入文件处理失败", e);
            throw new BizException(500, "导入文件处理失败");
        } finally {
            deleteQuietly(temp);
        }
    }

    // ------------------------------------------------------------------ 查询

    public ImportValidateResultDTO get(Long batchId) {
        ImportBatchEntity batch = requireBatch(batchId);
        dataScope.assertPeriodVisible(batch.getPeriodId());
        return toValidateResult(batch);
    }

    // ------------------------------------------------------------------ 提交

    @Transactional
    public ImportCommitResultDTO commit(Long batchId) {
        ImportBatchEntity existing = requireBatch(batchId);
        dataScope.assertPeriodVisible(existing.getPeriodId());
        // 写事务内锁期间行：预览之后被锁定的期间必须在这里被挡住
        periodService.assertWritableForUpdate(existing.getPeriodId());

        // 锁批次行，双击提交只有一次能成功
        ImportBatchEntity batch = batchMapper.selectForUpdate(batchId);
        if (batch == null) {
            throw new BizException(404, "导入批次不存在");
        }
        if (STATUS_COMMITTED.equals(batch.getStatus())) {
            throw new BizException(409, "该批次已提交，不能重复提交");
        }
        if (!STATUS_VALIDATED.equals(batch.getStatus())) {
            throw new BizException(400, "批次状态不允许提交：" + batch.getStatus());
        }
        if (batch.getFailRows() != null && batch.getFailRows() > 0) {
            throw new ImportConflictException(batchId, batch.getFailRows());
        }

        PayPeriodEntity period = periodService.requirePeriod(batch.getPeriodId());
        // 提交时重放 + 重新校验：期间状态、记录内容、科目字典都可能已经变了
        ImportValidator.Result result = validator.validate(Path.of(batch.getFilePath()),
                batch.getPeriodId(), period.getCompanyId(), batch.getBizType(),
                maxRows, maxErrorRows);
        if (result.failRows() > 0) {
            throw new ImportConflictException(batchId, result.failRows());
        }

        apply(period, batch, result);
        batch.setStatus(STATUS_COMMITTED);
        batch.setTotalRows(result.totalRows());
        batch.setSuccessRows(result.rows().size());
        batch.setFailRows(0);
        batch.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(batch);

        auditService.record("IMPORT", batchId, "UPDATE", "commit", null,
                "mode=" + batch.getImportMode() + ";rows=" + result.rows().size());
        return new ImportCommitResultDTO(batchId, STATUS_COMMITTED, result.rows().size(), 0);
    }

    private void apply(PayPeriodEntity period, ImportBatchEntity batch,
                       ImportValidator.Result result) {
        for (ImportValidator.ValidatedRow row : result.rows()) {
            for (Map.Entry<String, BigDecimal> entry : row.amounts().entrySet()) {
                upsertItem(period, batch.getImportMode(), row.recordId(),
                        entry.getKey(), entry.getValue(), result.itemDefs());
            }
            recalcRecord(row.recordId());
        }
        // 明细变了，期间合计与列快照必须同步刷新
        totalsRefresher.refresh(period);
    }

    private void upsertItem(PayPeriodEntity period, String mode, Long recordId,
                            String itemCode, BigDecimal amount,
                            Map<String, PayItemDefDTO> itemDefs) {
        PayRecordItemEntity existing = recordItemMapper.selectOne(Wrappers.<PayRecordItemEntity>lambdaQuery()
                .eq(PayRecordItemEntity::getRecordId, recordId)
                .eq(PayRecordItemEntity::getItemCode, itemCode)
                .last("LIMIT 1"));
        if (existing != null) {
            if (MODE_INSERT_ONLY.equals(mode)) {
                return;
            }
            existing.setAmount(MODE_APPEND.equals(mode) && existing.getAmount() != null
                    ? existing.getAmount().add(amount)
                    : amount);
            existing.setSource(SOURCE_IMPORT);
            recordItemMapper.updateById(existing);
            return;
        }

        PayItemDefDTO def = itemDefs.get(itemCode);
        PayRecordItemEntity entity = new PayRecordItemEntity();
        entity.setRecordId(recordId);
        entity.setItemCode(itemCode);
        entity.setItemName(def == null ? itemCode : def.itemName());
        entity.setCategory(def == null ? "VARIABLE" : def.category());
        entity.setDirection(def == null ? 1 : def.direction());
        entity.setAmount(amount);
        entity.setSource(SOURCE_IMPORT);
        entity.setSortNo(def == null || def.sortNo() == null ? 0 : def.sortNo());
        recordItemMapper.insert(entity);
    }

    /**
     * 重算一条记录的合计（与编制台同一口径：按 direction 分别加总，不强制定标）。
     * version_no +1 让打开着的编制台在保存时收到 409，而不是静默覆盖导入结果。
     */
    private void recalcRecord(Long recordId) {
        PayRecordEntity record = recordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        List<PayRecordItemEntity> items = recordItemMapper.findByRecordIds(List.of(recordId));
        BigDecimal gross = items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection() == 1)
                .map(PayRecordItemEntity::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deduction = items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection() == 2)
                .map(PayRecordItemEntity::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        record.setGrossAmount(gross);
        record.setDeductionAmount(deduction);
        record.setNetAmount(gross.subtract(deduction));
        record.setVersionNo(record.getVersionNo() == null ? 1 : record.getVersionNo() + 1);
        recordMapper.updateById(record);
    }

    // ------------------------------------------------------------------ 错误报告

    public PreparedExport errors(Long batchId) {
        CurrentUser current = requireUser();
        if (!current.has("payroll:export")) {
            throw new BizException(403, "无导出权限");
        }
        ImportBatchEntity batch = requireBatch(batchId);
        dataScope.assertPeriodVisible(batch.getPeriodId());

        List<ImportErrorEntity> errors = errorMapper.selectList(
                Wrappers.<ImportErrorEntity>lambdaQuery()
                        .eq(ImportErrorEntity::getBatchId, batchId)
                        .orderByAsc(ImportErrorEntity::getRowNo)
                        .orderByAsc(ImportErrorEntity::getId)
                        .last("LIMIT " + maxErrorRows));

        List<ExcelWriter.Column> columns = List.of(
                ExcelWriter.Column.integer("行号"),
                ExcelWriter.Column.text("列名"),
                ExcelWriter.Column.text("错误代码"),
                ExcelWriter.Column.text("原因"),
                ExcelWriter.Column.text("原值"));
        List<List<Object>> rows = new ArrayList<>();
        for (ImportErrorEntity error : errors) {
            rows.add(List.of(error.getRowNo(), nullSafe(error.getColumnName()),
                    error.getErrorCode(), error.getErrorMsg(), nullSafe(error.getRawValue())));
        }

        PreparedExport prepared;
        try {
            prepared = excelWriter.write("import-errors-" + batchId + "-" + STAMP.format(Instant.now()) + ".xlsx",
                    List.of(new ExcelWriter.SheetData("错误明细", columns, rows)));
        } catch (IOException e) {
            log.error("错误报告生成失败", e);
            throw new BizException(500, "错误报告生成失败");
        }
        auditService.record("EXPORT", batchId, "EXPORT", "IMPORT_ERRORS", null,
                "batchId=" + batchId + ";rows=" + rows.size());
        return prepared;
    }

    // ------------------------------------------------------------------ 内部

    private ImportBatchEntity requireBatch(Long batchId) {
        ImportBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException(404, "导入批次不存在");
        }
        return batch;
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
        return new ImportValidateResultDTO(batch.getId(), batch.getTotalRows(), batch.getSuccessRows(),
                batch.getFailRows(), batch.getStatus(), errors);
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_UPSERT;
        }
        String normalized = mode.trim().toUpperCase();
        return switch (normalized) {
            case MODE_UPSERT, MODE_APPEND, MODE_INSERT_ONLY -> normalized;
            default -> throw new BizException(400, "importMode 只能是 UPSERT/APPEND/INSERT_ONLY：" + mode);
        };
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "upload.xlsx";
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

    private static CellStyle boldStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle keyStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        font.setItalic(true);
        style.setFont(font);
        return style;
    }

    private static int header(Row titles, Row keys, int col, String title, String key,
                              CellStyle titleStyle, CellStyle keyStyle) {
        var titleCell = titles.createCell(col);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);
        var keyCell = keys.createCell(col);
        keyCell.setCellValue(key);
        keyCell.setCellStyle(keyStyle);
        return col + 1;
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

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }
}
