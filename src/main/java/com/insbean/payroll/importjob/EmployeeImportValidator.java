package com.insbean.payroll.importjob;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.importjob.dto.EmployeeImportRow;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.security.CryptoService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 人员批量导入的解析与预校验（工号/证件号查重、部门编码、枚举与日期格式）。
 *
 * <p>模板列顺序固定；列头必须逐列匹配，错位时直接 400，避免把"姓名"写进"工号"。
 */
@Component
public class EmployeeImportValidator {

    static final List<String> HEADERS = List.of(
            "工号", "姓名", "性别", "员工类型", "证件类型", "证件号码", "手机号",
            "部门编码", "职级", "岗位", "入职日期", "参加工作时间", "基本工资");

    private static final Set<String> EMPLOYEE_TYPES = Set.of(
            "REGULAR", "PROBATION", "INTERN", "LABOR", "OUTSOURCE", "REHIRE");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999.9999");
    private static final int MAX_RAW_VALUE = 512;

    private final EmployeeMapper employeeMapper;
    private final DeptMapper deptMapper;
    private final CryptoService cryptoService;

    public EmployeeImportValidator(EmployeeMapper employeeMapper, DeptMapper deptMapper,
                                   CryptoService cryptoService) {
        this.employeeMapper = employeeMapper;
        this.deptMapper = deptMapper;
        this.cryptoService = cryptoService;
    }

    public record Result(int totalRows, List<EmployeeImportRow> rows,
                         List<ImportValidator.RowError> errors, int failRows) {
    }

    public Result validate(Path file, Long companyId, int maxRows, int maxErrorRows) {
        validateHeaders(file);

        Collector collector = new Collector(companyId, maxRows, maxErrorRows);
        EasyExcel.read(file.toFile())
                .sheet(0)
                .headRowNumber(1)
                .registerReadListener(collector)
                .doRead();
        return new Result(collector.totalRows, List.copyOf(collector.rows),
                List.copyOf(collector.errors), collector.failedRows.size());
    }

    private void validateHeaders(Path file) {
        List<Map<Integer, String>> firstRow = new ArrayList<>();
        try {
            EasyExcel.read(file.toFile())
                    .sheet(0)
                    .headRowNumber(0)
                    .registerReadListener(new AnalysisEventListener<Map<Integer, String>>() {
                        @Override
                        public void invoke(Map<Integer, String> data, AnalysisContext context) {
                            firstRow.add(data);
                            context.interrupt();
                        }

                        @Override
                        public void doAfterAllAnalysed(AnalysisContext context) {
                        }
                    })
                    .doRead();
        } catch (RuntimeException e) {
            // EasyExcel 3.3.x 把 context.interrupt() 包成 ExcelAnalysisException；
            // 已经拿到表头就说明是预期的提前停止
            if (firstRow.isEmpty()) {
                throw e;
            }
        }
        if (firstRow.isEmpty()) {
            throw new BizException(400, "文件没有表头");
        }
        Map<Integer, String> row = firstRow.get(0);
        for (int i = 0; i < HEADERS.size(); i++) {
            String actual = trim(row.get(i));
            if (!HEADERS.get(i).equals(actual)) {
                throw new BizException(400, "模板列不匹配：第 " + (i + 1) + " 列应为「"
                        + HEADERS.get(i) + "」，实际为「" + actual + "」");
            }
        }
    }

    private final class Collector extends AnalysisEventListener<Map<Integer, String>> {
        private final Long companyId;
        private final int maxRows;
        private final int maxErrorRows;
        final List<EmployeeImportRow> rows = new ArrayList<>();
        final List<ImportValidator.RowError> errors = new ArrayList<>();
        final Set<Integer> failedRows = new LinkedHashSet<>();
        private final Set<String> seenEmpNo = new HashSet<>();
        private final Set<String> seenIdHash = new HashSet<>();
        int totalRows;

        private Collector(Long companyId, int maxRows, int maxErrorRows) {
            this.companyId = companyId;
            this.maxRows = maxRows;
            this.maxErrorRows = maxErrorRows;
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            if (data == null || data.values().stream().allMatch(v -> v == null || v.isBlank())) {
                return;
            }
            totalRows++;
            if (totalRows > maxRows) {
                throw new BizException(400, "文件行数超过上限 " + maxRows + " 行，请拆分后导入");
            }
            int excelRow = context.readRowHolder().getRowIndex() + 1;
            currentRow = excelRow;

            String empNo = trim(data.get(0));
            String name = trim(data.get(1));
            String genderRaw = trim(data.get(2));
            String typeRaw = trim(data.get(3)).toUpperCase(Locale.ROOT);
            String idType = trim(data.get(4)).isEmpty() ? "ID_CARD" : trim(data.get(4));
            String idNo = trim(data.get(5));
            String mobile = trim(data.get(6));
            String deptCode = trim(data.get(7));
            String jobLevel = trim(data.get(8));
            String position = trim(data.get(9));
            String hireRaw = trim(data.get(10));
            String workStartRaw = trim(data.get(11));
            String salaryRaw = trim(data.get(12));

            if (empNo.isEmpty()) {
                add(0, "工号", null, "REQUIRED", "工号不能为空");
            } else {
                if (!seenEmpNo.add(empNo)) {
                    add(0, "工号", empNo, "DUPLICATE_IN_FILE", "文件内工号重复");
                } else if (employeeMapper.countByEmpNo(empNo) > 0) {
                    add(0, "工号", empNo, "EMP_NO_EXISTS", "工号已存在");
                }
            }
            if (name.isEmpty()) {
                add(1, "姓名", null, "REQUIRED", "姓名不能为空");
            }

            Integer gender = null;
            try {
                gender = parseGender(genderRaw);
            } catch (IllegalArgumentException e) {
                add(2, "性别", genderRaw, "GENDER_INVALID", "性别只能是 男/女/未知 或 1/2/0");
            }
            if (typeRaw.isEmpty()) {
                add(3, "员工类型", null, "REQUIRED", "员工类型不能为空");
            } else if (!EMPLOYEE_TYPES.contains(typeRaw)) {
                add(3, "员工类型", typeRaw, "EMPLOYEE_TYPE_INVALID",
                        "员工类型只能是 " + EMPLOYEE_TYPES);
            }

            if (idNo.isEmpty()) {
                add(5, "证件号码", null, "REQUIRED", "证件号码不能为空");
            } else {
                String hash = cryptoService.blindIndex(idNo);
                if (!seenIdHash.add(hash)) {
                    add(5, "证件号码", CryptoService.maskIdNo(idNo), "DUPLICATE_IN_FILE",
                            "文件内证件号码重复");
                } else if (employeeMapper.countByIdNoHash(hash) > 0) {
                    add(5, "证件号码", CryptoService.maskIdNo(idNo), "ID_NO_EXISTS",
                            "该证件号码已存在人员记录");
                }
            }

            Long deptId = null;
            if (deptCode.isEmpty()) {
                add(7, "部门编码", null, "REQUIRED", "部门编码不能为空");
            } else {
                SysDeptEntity dept = deptMapper.findByCode(companyId, deptCode);
                if (dept == null) {
                    add(7, "部门编码", deptCode, "DEPT_NOT_FOUND", "部门编码不存在：" + deptCode);
                } else {
                    deptId = dept.getId();
                }
            }

            LocalDate hireDate = null;
            try {
                if (hireRaw.isEmpty()) {
                    add(10, "入职日期", null, "REQUIRED", "入职日期不能为空");
                } else {
                    hireDate = parseDate(hireRaw);
                }
            } catch (RuntimeException e) {
                add(10, "入职日期", hireRaw, "DATE_INVALID", "日期格式应为 YYYY-MM-DD");
            }

            LocalDate workStartDate = null;
            if (!workStartRaw.isEmpty()) {
                try {
                    workStartDate = parseDate(workStartRaw);
                } catch (RuntimeException e) {
                    add(11, "参加工作时间", workStartRaw, "DATE_INVALID", "日期格式应为 YYYY-MM-DD");
                }
            }

            BigDecimal baseSalary = null;
            try {
                if (salaryRaw.isEmpty()) {
                    add(12, "基本工资", null, "REQUIRED", "基本工资不能为空");
                } else {
                    baseSalary = new BigDecimal(salaryRaw.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
                    if (baseSalary.signum() < 0) {
                        add(12, "基本工资", salaryRaw, "AMOUNT_INVALID", "基本工资不能为负数");
                        baseSalary = null;
                    } else if (baseSalary.compareTo(MAX_AMOUNT) > 0) {
                        add(12, "基本工资", salaryRaw, "AMOUNT_OUT_OF_RANGE", "基本工资超出允许范围");
                        baseSalary = null;
                    }
                }
            } catch (NumberFormatException e) {
                add(12, "基本工资", salaryRaw, "AMOUNT_INVALID", "基本工资格式不正确");
            }

            if (!failedRows.contains(excelRow)) {
                rows.add(new EmployeeImportRow(excelRow, new EmployeeSaveReq(
                        companyId, empNo, name, gender, idType, idNo, mobile, typeRaw,
                        jobLevel.isEmpty() ? null : jobLevel,
                        position.isEmpty() ? null : position,
                        deptId, hireDate, workStartDate, null, 1, "批量导入"), baseSalary));
            }
        }

        private void add(int columnIndex, String column, String raw, String code, String message) {
            int excelRow = currentRow;
            failedRows.add(excelRow);
            if (errors.size() >= maxErrorRows) {
                return;
            }
            errors.add(new ImportValidator.RowError(excelRow, column,
                    raw == null ? null : raw.substring(0, Math.min(raw.length(), MAX_RAW_VALUE)),
                    code, message));
        }

        /** add() 需要一个行号：在 invoke 开头设置当前 Excel 行。 */
        private int currentRow;

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
        }
    }

    private static Integer parseGender(String raw) {
        return switch (raw) {
            case "", "未知", "0" -> 0;
            case "男", "1" -> 1;
            case "女", "2" -> 2;
            default -> throw new IllegalArgumentException(raw);
        };
    }

    private static LocalDate parseDate(String raw) {
        String normalized = raw.trim().replace('/', '-');
        try {
            return LocalDate.parse(normalized);
        } catch (RuntimeException e) {
            String[] parts = normalized.split("-");
            if (parts.length != 3) {
                throw e;
            }
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
