package com.insbean.payroll.importjob;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 变动数据导入的解析与预校验。
 *
 * <p>模板是两行表头：第 1 行给人看（工资项名称），第 2 行是机器键（itemCode）。
 * 只按名称匹配列会在科目改名后错位，机器键才是稳定标识。
 *
 * <p><b>预校验只读</b>：这里不写任何 pay_record / pay_record_item，
 * 提交时按批次重放并重新校验（文件可能在上传与提交之间被改过期间状态）。
 */
@Component
public class ImportValidator {

    public static final String KEY_EMP_NO = "empNo";
    public static final String KEY_EMP_NAME = "empName";

    private static final String TITLE_EMP_NO = "工号";
    private static final String TITLE_EMP_NAME = "姓名";
    private static final int MAX_RAW_VALUE = 512;

    private final PayRecordMapper recordMapper;
    private final ItemService itemService;

    public ImportValidator(PayRecordMapper recordMapper, ItemService itemService) {
        this.recordMapper = recordMapper;
        this.itemService = itemService;
    }

    public record ValidatedRow(int rowNo, Long recordId, Map<String, BigDecimal> amounts) {
    }

    public record RowError(int rowNo, String columnName, String rawValue,
                           String errorCode, String errorMsg) {
    }

    public record Result(int totalRows, List<ValidatedRow> rows, List<RowError> errors,
                         int failRows, Map<String, PayItemDefDTO> itemDefs) {
    }

    public Result validate(Path file, Long periodId, Long companyId,
                           String bizType, int maxRows, int maxErrorRows) {
        Map<String, PayItemDefDTO> itemDefs = importableItems(companyId, bizType);
        Map<String, Long> recordByEmpNo = recordByEmpNo(periodId, companyId);

        List<Map<Integer, String>> headRows = readHeadRows(file);
        boolean machineHeader = isMachineHeader(headRows);
        int headerRows = machineHeader ? 2 : 1;
        Map<Integer, String> columns = columnMapping(headRows, machineHeader, itemDefs);

        Collector collector = new Collector(maxRows, maxErrorRows, columns,
                recordByEmpNo, itemDefs);
        EasyExcel.read(file.toFile())
                .sheet(0)
                .headRowNumber(headerRows)
                .registerReadListener(collector)
                .doRead();
        return collector.toResult(itemDefs);
    }

    // ------------------------------------------------------------------ 表头

    private List<Map<Integer, String>> readHeadRows(Path file) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try {
            EasyExcel.read(file.toFile())
                    .sheet(0)
                    .headRowNumber(0)
                    .registerReadListener(new AnalysisEventListener<Map<Integer, String>>() {
                        @Override
                        public void invoke(Map<Integer, String> data, AnalysisContext context) {
                            rows.add(data);
                            if (rows.size() >= 2) {
                                context.interrupt();
                            }
                        }

                        @Override
                        public void doAfterAllAnalysed(AnalysisContext context) {
                        }
                    })
                    .doRead();
        } catch (RuntimeException e) {
            // EasyExcel 3.3.x 把 context.interrupt() 包装成 ExcelAnalysisException 抛出；
            // 已经拿到两行表头就说明是预期的提前停止，不是读取失败。
            if (rows.size() < 2) {
                throw e;
            }
        }
        if (rows.isEmpty()) {
            throw new BizException(400, "文件没有表头");
        }
        return rows;
    }

    /** 第二行含 empNo 时认定为"名称 + 机器键"两行表头。 */
    private static boolean isMachineHeader(List<Map<Integer, String>> headRows) {
        if (headRows.size() < 2) {
            return false;
        }
        return headRows.get(1).values().stream()
                .anyMatch(v -> v != null && KEY_EMP_NO.equalsIgnoreCase(v.trim()));
    }

    private static Map<Integer, String> columnMapping(List<Map<Integer, String>> headRows,
                                                      boolean machineHeader,
                                                      Map<String, PayItemDefDTO> itemDefs) {
        Map<Integer, String> titles = headRows.get(0);
        Map<Integer, String> keys = machineHeader ? headRows.get(1) : Map.of();
        Map<Integer, String> mapping = new LinkedHashMap<>();

        for (Map.Entry<Integer, String> entry : titles.entrySet()) {
            int index = entry.getKey();
            String title = trim(entry.getValue());
            String key = trim(keys.get(index));
            if (key.isEmpty()) {
                key = keyFromTitle(title, itemDefs);
            }
            if (KEY_EMP_NO.equalsIgnoreCase(key) || KEY_EMP_NAME.equalsIgnoreCase(key)) {
                mapping.put(index, key.equalsIgnoreCase(KEY_EMP_NO) ? KEY_EMP_NO : KEY_EMP_NAME);
                continue;
            }
            PayItemDefDTO item = itemDefs.get(key);
            if (item == null) {
                throw new BizException(400, "模板列与工资项字典不匹配（或该科目不允许在此导入类型中导入）："
                        + (key.isEmpty() ? title : key));
            }
            mapping.put(index, item.itemCode());
        }
        if (!mapping.containsValue(KEY_EMP_NO)) {
            throw new BizException(400, "模板缺少工号列");
        }
        return mapping;
    }

    private static String keyFromTitle(String title, Map<String, PayItemDefDTO> itemDefs) {
        if (title.isEmpty()) {
            return "";
        }
        if (TITLE_EMP_NO.equals(title) || "员工工号".equals(title)) {
            return KEY_EMP_NO;
        }
        if (TITLE_EMP_NAME.equals(title) || "员工姓名".equals(title)) {
            return KEY_EMP_NAME;
        }
        for (PayItemDefDTO item : itemDefs.values()) {
            if (title.equals(item.itemName())) {
                return item.itemCode();
            }
        }
        return title;
    }

    // ------------------------------------------------------------------ 行

    private final class Collector extends AnalysisEventListener<Map<Integer, String>> {

        private final int maxRows;
        private final int maxErrorRows;
        private final Map<Integer, String> columns;
        private final Map<String, Long> recordByEmpNo;
        private final Map<String, PayItemDefDTO> itemDefs;
        private final List<ValidatedRow> validRows = new ArrayList<>();
        private final List<RowError> errors = new ArrayList<>();
        private final Set<Integer> failedRows = new LinkedHashSet<>();
        private final Set<String> seenEmpNo = new LinkedHashSet<>();
        private int totalRows;

        private Collector(int maxRows, int maxErrorRows, Map<Integer, String> columns,
                          Map<String, Long> recordByEmpNo, Map<String, PayItemDefDTO> itemDefs) {
            this.maxRows = maxRows;
            this.maxErrorRows = maxErrorRows;
            this.columns = columns;
            this.recordByEmpNo = recordByEmpNo;
            this.itemDefs = itemDefs;
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            if (isBlankRow(data)) {
                return;
            }
            totalRows++;
            if (totalRows > maxRows) {
                throw new BizException(400, "文件行数超过上限 " + maxRows + " 行，请拆分后导入");
            }
            int excelRow = context.readRowHolder().getRowIndex() + 1;

            String empNo = trim(data.get(indexOf(KEY_EMP_NO)));
            if (empNo.isEmpty()) {
                addError(excelRow, TITLE_EMP_NO, null, "EMP_NO_REQUIRED", "工号不能为空");
                return;
            }
            if (!seenEmpNo.add(empNo)) {
                addError(excelRow, TITLE_EMP_NO, empNo, "DUPLICATE_IN_FILE",
                        "文件内工号重复，第 " + excelRow + " 行之前已出现");
                return;
            }
            Long recordId = recordByEmpNo.get(empNo);
            if (recordId == null) {
                addError(excelRow, TITLE_EMP_NO, empNo, "NO_RECORD",
                        "该工号在本期间没有核算记录（导入只修改已有记录，不创建记录）");
                return;
            }

            Map<String, BigDecimal> amounts = new LinkedHashMap<>();
            boolean rowFailed = false;
            for (Map.Entry<Integer, String> column : columns.entrySet()) {
                String key = column.getValue();
                if (KEY_EMP_NO.equals(key) || KEY_EMP_NAME.equals(key)) {
                    continue;
                }
                String raw = data.get(column.getKey());
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                PayItemDefDTO def = itemDefs.get(key);
                String cleaned = raw.trim().replace(",", "").replace(" ", "");
                try {
                    BigDecimal amount = new BigDecimal(cleaned)
                            .setScale(def.precisionScale() == null ? 2 : def.precisionScale(),
                                    RoundingMode.HALF_UP);
                    if (amount.signum() < 0) {
                        addError(excelRow, def.itemName(), raw, "AMOUNT_NEGATIVE",
                                "金额不能为负数，扣款请使用减项科目");
                        rowFailed = true;
                        continue;
                    }
                    amounts.put(key, amount);
                } catch (NumberFormatException e) {
                    addError(excelRow, def.itemName(), raw, "AMOUNT_INVALID", "金额格式不正确");
                    rowFailed = true;
                }
            }
            if (!rowFailed) {
                validRows.add(new ValidatedRow(excelRow, recordId, amounts));
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
        }

        private void addError(int rowNo, String column, String raw, String code, String message) {
            failedRows.add(rowNo);
            if (errors.size() >= maxErrorRows) {
                return;
            }
            errors.add(new RowError(rowNo, column,
                    raw == null ? null : raw.substring(0, Math.min(raw.length(), MAX_RAW_VALUE)),
                    code, message));
        }

        private int indexOf(String key) {
            for (Map.Entry<Integer, String> entry : columns.entrySet()) {
                if (key.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
            return -1;
        }

        private boolean isBlankRow(Map<Integer, String> data) {
            return data == null || data.values().stream().allMatch(v -> v == null || v.isBlank());
        }

        private Result toResult(Map<String, PayItemDefDTO> defs) {
            return new Result(totalRows, List.copyOf(validRows), List.copyOf(errors),
                    failedRows.size(), defs);
        }
    }

    // ------------------------------------------------------------------ 查询

    private Map<String, PayItemDefDTO> importableItems(Long companyId, String bizType) {
        String category = "FIXED".equals(bizType) ? "FIXED" : "VARIABLE";
        Map<String, PayItemDefDTO> result = new LinkedHashMap<>();
        for (PayItemDefDTO item : itemService.list(companyId, category, 1)) {
            if (Boolean.TRUE.equals(item.importable())) {
                result.put(item.itemCode(), item);
            }
        }
        return result;
    }

    private Map<String, Long> recordByEmpNo(Long periodId, Long companyId) {
        Map<String, Long> result = new HashMap<>();
        List<PayRecordEntity> records = recordMapper.selectList(Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getPeriodId, periodId)
                .eq(PayRecordEntity::getCompanyId, companyId)
                .eq(PayRecordEntity::getDeleted, 0));
        for (PayRecordEntity record : records) {
            result.put(record.getEmpNo(), record.getId());
        }
        return result;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 供测试与错误信息使用：导入类型 → 工资项类别。 */
    public static String categoryOf(String bizType) {
        return switch (bizType == null ? "" : bizType.toUpperCase(Locale.ROOT)) {
            case "FIXED" -> "FIXED";
            case "VARIABLE" -> "VARIABLE";
            default -> throw new BizException(400, "bizType 只能是 FIXED 或 VARIABLE：" + bizType);
        };
    }
}
