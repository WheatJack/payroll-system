package com.insbean.payroll.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.domain.entity.SysCompanyEntity;
import com.insbean.payroll.org.mapper.CompanyMapper;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.record.ColumnSnapshotFactory;
import com.insbean.payroll.record.dto.GridColumn;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.report.dto.AnnualReportRow;
import com.insbean.payroll.report.dto.ExportRequest;
import com.insbean.payroll.report.dto.MonthlyReportRow;
import com.insbean.payroll.report.dto.ReportExportParams;
import com.insbean.payroll.report.dto.ReportQuery;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import com.insbean.payroll.tax.AnnualTaxReportService;
import com.insbean.payroll.tax.dto.AnnualTaxRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 报表导出（契约 POST /reports/export）。
 *
 * <p>四种报表共用一条"先准备、后落盘、再写审计"的流水线：
 * prepare 全部成功之后才写审计与响应头，任何一步失败都不会留下"半截二进制响应"。
 *
 * <p>PERIOD_SHEET 的列取 {@code pay_period.column_snapshot} 而不是当前启用科目：
 * 明细可能含生成后停用的科目，按当前字典导列会和合计对不上。
 * PAYSLIP 只含姓名/工号/部门/工资项/合计，不含证件号、手机号、银行卡。
 */
@Slf4j
@Service
public class ExportService {

    private static final String XLSX = "XLSX";
    private static final String PERIOD_YM_PATTERN = "^[0-9]{4}-(0[1-9]|1[0-2])$";
    private static final String YEAR_PATTERN = "^[0-9]{4}$";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneId.systemDefault());

    private final ReportService reportService;
    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final PeriodMapper periodMapper;
    private final CompanyMapper companyMapper;
    private final ColumnSnapshotFactory columnSnapshotFactory;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final DataScopeEnforcer dataScope;
    private final ExcelWriter excelWriter;
    private final AnnualTaxReportService annualTaxReportService;

    public ExportService(ReportService reportService,
                         PayRecordMapper recordMapper,
                         PayRecordItemMapper recordItemMapper,
                         PeriodMapper periodMapper,
                         CompanyMapper companyMapper,
                         ColumnSnapshotFactory columnSnapshotFactory,
                         ObjectMapper objectMapper,
                         AuditService auditService,
                         DataScopeEnforcer dataScope,
                         ExcelWriter excelWriter,
                         AnnualTaxReportService annualTaxReportService) {
        this.reportService = reportService;
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.periodMapper = periodMapper;
        this.companyMapper = companyMapper;
        this.columnSnapshotFactory = columnSnapshotFactory;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.dataScope = dataScope;
        this.excelWriter = excelWriter;
        this.annualTaxReportService = annualTaxReportService;
    }

    public PreparedExport export(ExportRequest request) {
        CurrentUser me = requireUser();
        // 拦截器已按 @RequirePerm 拦过一层；服务层再校验一次，保证直接调用也安全
        if (!me.has("payroll:export")) {
            throw new BizException(403, "无导出权限");
        }
        if (request == null || request.reportType() == null) {
            throw new BizException(400, "reportType 必填");
        }
        if (request.format() != null && !XLSX.equalsIgnoreCase(request.format())) {
            throw new BizException(400, "当前仅支持 XLSX 格式");
        }
        ReportExportParams params = request.params();
        if (params == null) {
            throw new BizException(400, "params 必填");
        }

        String type = request.reportType().trim().toUpperCase();
        String stamp = STAMP.format(Instant.now());
        List<ExcelWriter.SheetData> sheets;
        long dataRows;
        String filename;
        String description;

        switch (type) {
            case "MONTHLY" -> {
                requirePeriodYm(params);
                List<MonthlyReportRow> rows = reportService.monthly(
                        ReportQuery.monthly(params.periodYm(), params.companyId(), params.deptId())).rows();
                dataRows = rows.size();
                sheets = List.of(monthlySheet(rows));
                filename = "monthly-" + params.periodYm() + "-" + stamp + ".xlsx";
                description = describe(type, params);
            }
            case "ANNUAL" -> {
                requireYearAndGroupBy(params);
                List<AnnualReportRow> rows = reportService.annual(ReportQuery.annual(
                        params.year(), params.groupBy(), params.companyId(), params.deptId())).rows();
                Long sheetCompanyId = params.companyId() != null
                        ? params.companyId()
                        : (DataScopeEnforcer.ALL.equals(me.dataScope()) ? null : me.companyId());
                dataRows = rows.size();
                sheets = List.of(annualSheet(params.year(), rows, sheetCompanyId));
                String group = rows.isEmpty() ? "EMP" : rows.get(0).groupBy();
                filename = "annual-" + params.year() + "-" + group + "-" + stamp + ".xlsx";
                description = describe(type, params);
            }
            case "ANNUAL_TAX" -> {
                if (params.year() == null || !params.year().matches(YEAR_PATTERN)) {
                    throw new BizException(400, "year 必填且格式为 YYYY");
                }
                List<AnnualTaxRow> rows = annualTaxReportService.annualTax(
                        params.year(), params.employeeIds(), params.deptId()).rows();
                dataRows = rows.size();
                sheets = List.of(annualTaxSheet(rows));
                filename = "annual-tax-" + params.year() + "-" + stamp + ".xlsx";
                description = describe(type, params);
            }
            case "PERIOD_SHEET" -> {
                requirePeriodYm(params);
                PeriodData periodData = periodSheets(params);
                sheets = periodData.sheets();
                dataRows = periodData.rows();
                description = describe(type, params) + periodData.extraAudit();
                filename = "period-sheet-" + params.periodYm() + "-" + stamp + ".xlsx";
            }
            case "PAYSLIP" -> {
                requirePeriodYm(params);
                PeriodData periodData = payslipSheets(params);
                sheets = periodData.sheets();
                dataRows = periodData.rows();
                description = describe(type, params) + periodData.extraAudit();
                filename = "payslip-" + params.periodYm() + "-" + stamp + ".xlsx";
            }
            default -> throw new BizException(400, "不支持的报表类型：" + request.reportType());
        }

        PreparedExport prepared;
        try {
            prepared = excelWriter.write(filename, sheets);
        } catch (IOException e) {
            log.error("导出文件写出失败", e);
            throw new BizException(500, "导出文件生成失败");
        }
        // 审计在响应头之前写：写不进去就不该把文件发出去（合规要求）
        try {
            // rows 记业务数据行，不含"没有已锁定数据"的说明行
            auditService.record("EXPORT", null, "EXPORT", type, null,
                    description + ";rows=" + dataRows);
        } catch (RuntimeException e) {
            // 审计失败必须清理临时文件（含全量工资数据）再抛出
            try {
                java.nio.file.Files.deleteIfExists(prepared.path());
            } catch (IOException ignored) {
                log.warn("导出临时文件清理失败：{}", prepared.path());
            }
            throw e;
        }
        return prepared;
    }

    // ------------------------------------------------------------------ MONTHLY / ANNUAL

    private ExcelWriter.SheetData monthlySheet(List<MonthlyReportRow> rows) {
        List<ExcelWriter.Column> columns = List.of(
                ExcelWriter.Column.text("法人主体"),
                ExcelWriter.Column.text("期间"),
                ExcelWriter.Column.text("部门"),
                ExcelWriter.Column.integer("人数"),
                ExcelWriter.Column.money("应发合计"),
                ExcelWriter.Column.money("扣款合计"),
                ExcelWriter.Column.money("实发合计"));
        List<List<Object>> data = rows.isEmpty()
                ? List.of(listOf("该期间没有已锁定数据"))
                : rows.stream()
                .map(r -> listOf(companyName(r.companyId()), r.periodYm(),
                        r.deptName() == null ? String.valueOf(r.deptId()) : r.deptName(),
                        r.employeeCount(), r.grossAmount(), r.deductionAmount(), r.netAmount()))
                .toList();
        return new ExcelWriter.SheetData("月度汇总", columns, data);
    }

    private ExcelWriter.SheetData annualSheet(String year, List<AnnualReportRow> rows, Long sheetCompanyId) {
        boolean byEmployee = rows.isEmpty() || "EMP".equals(rows.get(0).groupBy());
        List<ExcelWriter.Column> columns = new ArrayList<>();
        columns.add(ExcelWriter.Column.text("年份"));
        if (byEmployee) {
            columns.add(ExcelWriter.Column.text("工号"));
            columns.add(ExcelWriter.Column.text("姓名"));
            columns.add(ExcelWriter.Column.integer("月份数"));
        } else {
            columns.add(ExcelWriter.Column.text("法人主体"));
            columns.add(ExcelWriter.Column.text("部门"));
            columns.add(ExcelWriter.Column.integer("人数"));
        }
        columns.add(ExcelWriter.Column.money("应发合计"));
        columns.add(ExcelWriter.Column.money("扣款合计"));
        columns.add(ExcelWriter.Column.money("实发合计"));

        List<List<Object>> data = rows.isEmpty()
                ? List.of(List.of("该年度没有已锁定数据"))
                : rows.stream().map(r -> {
            List<Object> row = new ArrayList<>();
            row.add(year);
            if (byEmployee) {
                row.add(r.empNo());
                row.add(r.empName());
                row.add(r.monthCount());
            } else {
                row.add(companyName(sheetCompanyId));
                row.add(r.deptName() == null ? String.valueOf(r.deptId()) : r.deptName());
                row.add(r.employeeCount());
            }
            row.add(r.grossAmount());
            row.add(r.deductionAmount());
            row.add(r.netAmount());
            return row;
        }).toList();
        return new ExcelWriter.SheetData(byEmployee ? "年度汇总-按人" : "年度汇总-按部门", columns, data);
    }

    private ExcelWriter.SheetData annualTaxSheet(List<AnnualTaxRow> rows) {
        List<ExcelWriter.Column> columns = List.of(
                ExcelWriter.Column.text("工号"),
                ExcelWriter.Column.text("姓名"),
                ExcelWriter.Column.text("部门"),
                ExcelWriter.Column.integer("计薪月数"),
                ExcelWriter.Column.money("累计收入"),
                ExcelWriter.Column.money("累计减除费用"),
                ExcelWriter.Column.money("累计社保"),
                ExcelWriter.Column.money("累计公积金"),
                ExcelWriter.Column.money("累计专项附加扣除"),
                ExcelWriter.Column.money("应纳税所得额"),
                ExcelWriter.Column.money("累计已预扣个税"),
                ExcelWriter.Column.money("最近一期个税"),
                ExcelWriter.Column.rate("实际税负率"));
        List<List<Object>> data = rows.stream().map(r -> listOf(
                r.empNo(), r.empName(), r.deptName(), r.paidMonths(), r.cumIncome(),
                r.cumBasicDeduction(), r.cumSocial(), r.cumFund(), r.cumSpecial(), r.taxable(),
                r.cumTax(), r.lastTax(), r.effectiveRate())).toList();
        return new ExcelWriter.SheetData("年度个税核对", columns, data);
    }

    // ------------------------------------------------------------------ PERIOD_SHEET

    private PeriodData periodSheets(ReportExportParams params) {
        assertPeriodLocked(params.periodYm());
        List<PayRecordEntity> records = lockedRecords(params.periodYm(), params.deptId(), null);
        if (records.isEmpty()) {
            return new PeriodData(List.of(emptyPaySheet("工资表-" + params.periodYm())), 0, "");
        }
        Map<Long, List<PayRecordEntity>> byCompany = records.stream()
                .collect(Collectors.groupingBy(PayRecordEntity::getCompanyId, TreeMap::new, Collectors.toList()));
        Map<Long, Map<String, BigDecimal>> items = itemAmounts(records);

        List<ExcelWriter.SheetData> sheets = new ArrayList<>();
        byCompany.forEach((companyId, companyRecords) -> {
            List<PayRecordItemEntity> companyItems = flattenItems(companyRecords, items);
            List<GridColumn> itemColumns = columnsFor(companyId, params.periodYm(), companyItems);
            sheets.add(paySheet(sheetName("工资表", companyId, params.periodYm()),
                    itemColumns, companyRecords, items, companyId, params.periodYm()));
        });
        return new PeriodData(sheets, records.size(), "");
    }

    /** sheets + 业务数据行数（审计用；说明行不计入）。 */
    private record PeriodData(List<ExcelWriter.SheetData> sheets, long rows, String extraAudit) {
    }

    private ExcelWriter.SheetData emptyPaySheet(String name) {
        List<ExcelWriter.Column> columns = List.of(
                ExcelWriter.Column.text("工号"),
                ExcelWriter.Column.text("姓名"),
                ExcelWriter.Column.text("部门"),
                ExcelWriter.Column.money("应发合计"),
                ExcelWriter.Column.money("扣款合计"),
                ExcelWriter.Column.money("实发合计"));
        // 空表也要写一行说明，避免用户把"没有数据"误判成"期间没有工资"
        return new ExcelWriter.SheetData(name, columns, List.of(List.of("该期间没有已锁定数据")));
    }

    // ------------------------------------------------------------------ PAYSLIP

    private PeriodData payslipSheets(ReportExportParams params) {
        assertPeriodLocked(params.periodYm());
        List<Long> employeeIds = params.employeeIds();
        List<PayRecordEntity> records = lockedRecords(params.periodYm(), params.deptId(), employeeIds);
        if (records.isEmpty()) {
            // 指定了人却一个都没匹配到：不能返回空表让用户以为导出成功
            if (employeeIds != null && !employeeIds.isEmpty()) {
                throw new BizException(400, "所选员工在该期间没有工资记录");
            }
            return new PeriodData(List.of(emptyPaySheet("工资条-" + params.periodYm())), 0, "");
        }
        long invalidEmployeeIds = employeeIds == null ? 0 : employeeIds.stream()
                .distinct()
                .filter(id -> records.stream().noneMatch(r -> id.equals(r.getEmployeeId())))
                .count();
        Map<Long, Map<String, BigDecimal>> items = itemAmounts(records);

        // 工资条跨主体时列取并集（同名列只出现一次），保证每行都对齐
        Map<String, GridColumn> union = new LinkedHashMap<>();
        Map<Long, List<PayRecordEntity>> byCompany = records.stream()
                .collect(Collectors.groupingBy(PayRecordEntity::getCompanyId, TreeMap::new, Collectors.toList()));
        byCompany.forEach((companyId, companyRecords) ->
                columnsFor(companyId, params.periodYm(), flattenItems(companyRecords, items))
                        .forEach(c -> union.putIfAbsent(c.itemCode(), c)));

        return new PeriodData(List.of(payslipSheet("工资条-" + params.periodYm(),
                new ArrayList<>(union.values()), records, items)), records.size(),
                invalidEmployeeIds > 0 ? ";invalidEmployeeIds=" + invalidEmployeeIds : "");
    }

    // ------------------------------------------------------------------ 共用

    /**
     * 期间工资表：带「法人主体」「期间」列（多主体导出时分得清），末尾追加「合计」行。
     */
    private ExcelWriter.SheetData paySheet(String name, List<GridColumn> itemColumns,
                                           List<PayRecordEntity> records,
                                           Map<Long, Map<String, BigDecimal>> itemsByRecord,
                                           Long companyId, String periodYm) {
        List<ExcelWriter.Column> columns = new ArrayList<>();
        columns.add(ExcelWriter.Column.text("法人主体"));
        columns.add(ExcelWriter.Column.text("期间"));
        columns.add(ExcelWriter.Column.text("工号"));
        columns.add(ExcelWriter.Column.text("姓名"));
        columns.add(ExcelWriter.Column.text("部门"));
        itemColumns.forEach(c -> columns.add(ExcelWriter.Column.money(c.itemName())));
        columns.add(ExcelWriter.Column.money("应发合计"));
        columns.add(ExcelWriter.Column.money("扣款合计"));
        columns.add(ExcelWriter.Column.money("实发合计"));

        String companyName = companyName(companyId);
        BigDecimal[] itemSums = new BigDecimal[itemColumns.size()];
        java.util.Arrays.fill(itemSums, BigDecimal.ZERO);
        BigDecimal grossSum = BigDecimal.ZERO;
        BigDecimal deductionSum = BigDecimal.ZERO;
        BigDecimal netSum = BigDecimal.ZERO;

        List<List<Object>> rows = new ArrayList<>();
        for (PayRecordEntity r : records) {
            Map<String, BigDecimal> items = itemsByRecord.getOrDefault(r.getId(), Map.of());
            List<Object> row = new ArrayList<>();
            row.add(companyName);
            row.add(periodYm);
            row.add(r.getEmpNo());
            row.add(r.getEmpName());
            row.add(r.getDeptName());
            for (int i = 0; i < itemColumns.size(); i++) {
                BigDecimal amount = items.get(itemColumns.get(i).itemCode());
                row.add(amount);
                if (amount != null) {
                    itemSums[i] = itemSums[i].add(amount);
                }
            }
            row.add(r.getGrossAmount());
            row.add(r.getDeductionAmount());
            row.add(r.getNetAmount());
            grossSum = grossSum.add(nvl(r.getGrossAmount()));
            deductionSum = deductionSum.add(nvl(r.getDeductionAmount()));
            netSum = netSum.add(nvl(r.getNetAmount()));
            rows.add(row);
        }

        if (!records.isEmpty()) {
            List<Object> totalRow = new ArrayList<>();
            totalRow.add("合计");
            totalRow.add(null);
            totalRow.add(null);
            totalRow.add(null);
            totalRow.add(null);
            for (BigDecimal sum : itemSums) {
                totalRow.add(sum.signum() == 0 ? (Object) null : sum);
            }
            totalRow.add(grossSum);
            totalRow.add(deductionSum);
            totalRow.add(netSum);
            rows.add(totalRow);
        }
        return new ExcelWriter.SheetData(name, columns, rows);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 工资条：长表 + 按员工分块（块间空行）。一列一个工资项会让上百列不可读，
     * 长表也天然只含姓名/工号/部门/工资项/金额，不会带出证件号等敏感字段。
     */
    private ExcelWriter.SheetData payslipSheet(String name, List<GridColumn> itemColumns,
                                               List<PayRecordEntity> records,
                                               Map<Long, Map<String, BigDecimal>> itemsByRecord) {
        List<ExcelWriter.Column> columns = List.of(
                ExcelWriter.Column.text("工号"),
                ExcelWriter.Column.text("姓名"),
                ExcelWriter.Column.text("部门"),
                ExcelWriter.Column.text("工资项"),
                ExcelWriter.Column.money("金额"));
        Map<String, String> itemNames = new java.util.LinkedHashMap<>();
        itemColumns.forEach(c -> itemNames.put(c.itemCode(), c.itemName()));

        List<List<Object>> rows = new ArrayList<>();
        for (PayRecordEntity record : records) {
            Map<String, BigDecimal> items = itemsByRecord.getOrDefault(record.getId(), Map.of());
            for (Map.Entry<String, BigDecimal> item : items.entrySet()) {
                rows.add(List.of(record.getEmpNo(), record.getEmpName(), record.getDeptName(),
                        itemNames.getOrDefault(item.getKey(), item.getKey()), item.getValue()));
            }
            rows.add(List.of(record.getEmpNo(), record.getEmpName(), record.getDeptName(),
                    "应发合计", nvl(record.getGrossAmount())));
            rows.add(List.of(record.getEmpNo(), record.getEmpName(), record.getDeptName(),
                    "扣款合计", nvl(record.getDeductionAmount())));
            rows.add(List.of(record.getEmpNo(), record.getEmpName(), record.getDeptName(),
                    "实发合计", nvl(record.getNetAmount())));
            rows.add(List.of()); // 员工块之间的空行
        }
        return new ExcelWriter.SheetData(name, columns, rows);
    }

    /**
     * 编制中（DRAFT/EDITING）的期间不得导出完整工资表/工资条。
     *
     * <p>以前是过滤后返回空表 + 200，前端会以为"导出成功但没数据"；
     * 这里明确 409，和"报表只统计已锁定期间"的口径一致。
     */
    private void assertPeriodLocked(String periodYm) {
        CurrentUser current = requireUser();
        List<PayPeriodEntity> visible = periodMapper.selectList(Wrappers.<PayPeriodEntity>lambdaQuery()
                        .eq(PayPeriodEntity::getPeriodYm, periodYm))
                .stream()
                .filter(p -> DataScopeEnforcer.ALL.equals(current.dataScope())
                        || current.companyId().equals(p.getCompanyId()))
                .toList();
        // 期间不存在必须 404：否则"2099-01"会返回空工作簿 200，用户以为该月没有工资
        if (visible.isEmpty()) {
            throw new BizException(404, "期间不存在：" + periodYm);
        }
        boolean anyUnlocked = visible.stream()
                .anyMatch(p -> !"LOCKED".equals(p.getStatus()) && !"ARCHIVED".equals(p.getStatus()));
        if (anyUnlocked) {
            throw new BizException(409, "期间尚未锁定，不能导出完整工资表或工资条");
        }
    }

    /** 已锁定/已归档期间、未删除、且通过数据范围的核算记录。 */
    private List<PayRecordEntity> lockedRecords(String periodYm, Long deptId, List<Long> employeeIds) {
        LambdaQueryWrapper<PayRecordEntity> query = Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getDeleted, 0)
                .eq(PayRecordEntity::getPeriodYm, periodYm)
                .inSql(PayRecordEntity::getPeriodId, ReportService.LOCKED_PERIODS_SQL)
                .orderByAsc(PayRecordEntity::getCompanyId)
                .orderByAsc(PayRecordEntity::getDeptId)
                .orderByAsc(PayRecordEntity::getEmpNo);
        if (deptId != null) {
            query.eq(PayRecordEntity::getDeptId, deptId);
        }
        if (employeeIds != null && !employeeIds.isEmpty()) {
            query.in(PayRecordEntity::getEmployeeId, employeeIds);
        }
        dataScope.applyRecordFilter(query);
        return recordMapper.selectList(query);
    }

    private Map<Long, Map<String, BigDecimal>> itemAmounts(List<PayRecordEntity> records) {
        Map<Long, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        if (records.isEmpty()) {
            return result;
        }
        List<PayRecordItemEntity> items = recordItemMapper.findByRecordIds(
                records.stream().map(PayRecordEntity::getId).toList());
        Map<Long, Map<String, BigDecimal>> byRecord = new LinkedHashMap<>();
        for (PayRecordItemEntity item : items) {
            byRecord.computeIfAbsent(item.getRecordId(), k -> new LinkedHashMap<>())
                    .put(item.getItemCode(), item.getAmount());
        }
        for (PayRecordEntity record : records) {
            result.put(record.getId(), byRecord.getOrDefault(record.getId(), Map.of()));
        }
        return result;
    }

    private static List<PayRecordItemEntity> flattenItems(
            List<PayRecordEntity> records,
            Map<Long, Map<String, BigDecimal>> itemsByRecord) {
        List<PayRecordItemEntity> flattened = new ArrayList<>();
        for (PayRecordEntity record : records) {
            Map<String, BigDecimal> items = itemsByRecord.getOrDefault(record.getId(), Map.of());
            items.forEach((code, amount) -> {
                PayRecordItemEntity entity = new PayRecordItemEntity();
                entity.setRecordId(record.getId());
                entity.setItemCode(code);
                entity.setAmount(amount);
                flattened.add(entity);
            });
        }
        return flattened;
    }

    /**
     * 工资表列：优先用期间生成时写下的 column_snapshot（含已停用但有明细的科目）；
     * 快照缺失时才退回"启用科目 ∪ 本期有明细的科目"。
     */
    private List<GridColumn> columnsFor(Long companyId, String periodYm, List<PayRecordItemEntity> items) {
        PayPeriodEntity period = periodMapper.selectOne(Wrappers.<PayPeriodEntity>lambdaQuery()
                .eq(PayPeriodEntity::getCompanyId, companyId)
                .eq(PayPeriodEntity::getPeriodYm, periodYm)
                .last("LIMIT 1"));
        if (period != null && period.getColumnSnapshot() != null && !period.getColumnSnapshot().isBlank()) {
            try {
                List<GridColumn> snapshot = objectMapper.readValue(period.getColumnSnapshot(),
                        new TypeReference<>() {
                        });
                if (!snapshot.isEmpty()) {
                    return snapshot;
                }
            } catch (IOException e) {
                log.warn("列快照解析失败，退回当前科目：periodId={}", period.getId(), e);
            }
        }
        return columnSnapshotFactory.build(companyId, items);
    }

    private String sheetName(String prefix, Long companyId, String periodYm) {
        SysCompanyEntity company = companyMapper.selectById(companyId);
        String code = company == null ? String.valueOf(companyId) : company.getCompanyCode();
        return prefix + "-" + code + "-" + periodYm;
    }

    private String companyName(Long companyId) {
        if (companyId == null) {
            return "";
        }
        String name = companyMapper.findNameById(companyId);
        return name == null ? String.valueOf(companyId) : name;
    }

    private static void requirePeriodYm(ReportExportParams params) {
        if (params.periodYm() == null || !params.periodYm().matches(PERIOD_YM_PATTERN)) {
            throw new BizException(400, "periodYm 必填且格式为 YYYY-MM");
        }
    }

    private static void requireYearAndGroupBy(ReportExportParams params) {
        if (params.year() == null || !params.year().matches(YEAR_PATTERN)) {
            throw new BizException(400, "year 必填且格式为 YYYY");
        }
        if (params.groupBy() == null || params.groupBy().isBlank()) {
            throw new BizException(400, "groupBy 必填（EMP 或 DEPT）");
        }
        // 与查询接口同一套校验（含 companyId 必填规则）
        ReportService.normalizeGroupBy(params.groupBy());
    }

    /**
     * 审计里的查询条件必须与实际生效的过滤条件一致。
     * PERIOD_SHEET/PAYSLIP 根本不接受 companyId，写进去反而会让人误以为已按主体过滤。
     */
    private static String describe(String type, ReportExportParams params) {
        StringBuilder sb = new StringBuilder("type=").append(type);
        switch (type) {
            case "MONTHLY" -> {
                sb.append(";periodYm=").append(params.periodYm());
                if (params.companyId() != null) {
                    sb.append(";companyId=").append(params.companyId());
                }
                if (params.deptId() != null) {
                    sb.append(";deptId=").append(params.deptId());
                }
            }
            case "ANNUAL" -> {
                sb.append(";year=").append(params.year());
                sb.append(";groupBy=").append(params.groupBy());
                if (params.companyId() != null) {
                    sb.append(";companyId=").append(params.companyId());
                }
                if (params.deptId() != null) {
                    sb.append(";deptId=").append(params.deptId());
                }
            }
            case "ANNUAL_TAX" -> {
                sb.append(";year=").append(params.year());
                if (params.deptId() != null) {
                    sb.append(";deptId=").append(params.deptId());
                }
                if (params.employeeIds() != null) {
                    sb.append(";employeeIds=").append(params.employeeIds());
                }
            }
            case "PERIOD_SHEET" -> {
                sb.append(";periodYm=").append(params.periodYm());
                if (params.deptId() != null) {
                    sb.append(";deptId=").append(params.deptId());
                }
            }
            case "PAYSLIP" -> {
                sb.append(";periodYm=").append(params.periodYm());
                if (params.deptId() != null) {
                    sb.append(";deptId=").append(params.deptId());
                }
                if (params.employeeIds() != null) {
                    sb.append(";employeeIds=").append(params.employeeIds());
                }
            }
            default -> {
                // 不会走到；未知类型在前面已被 400 拦截
            }
        }
        return sb.toString();
    }

    private static List<Object> listOf(Object... values) {
        List<Object> list = new ArrayList<>(values.length);
        for (Object value : values) {
            list.add(value);
        }
        return list;
    }

    private static CurrentUser requireUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return current;
    }
}
