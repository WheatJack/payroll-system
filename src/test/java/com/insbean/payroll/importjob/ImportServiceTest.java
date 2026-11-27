package com.insbean.payroll.importjob;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.importjob.dto.ImportCommitResultDTO;
import com.insbean.payroll.importjob.dto.ImportValidateResultDTO;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.report.PreparedExport;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 变动数据导入（Task 21）验收。
 *
 * <p>重点：预校验不落核算数据、同一文件二次上传 409、失败行禁止提交、
 * 提交时重放并只改已有记录、FIXED 覆盖与 APPEND 累加。
 */
class ImportServiceTest extends AbstractDbTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private ImportService importService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private JdbcTemplate jdbc;

    private long periodId;

    @BeforeEach
    void setUpPayroll() {
        for (int i = 1; i <= 3; i++) {
            Long empId = employeeService.create(new EmployeeSaveReq(
                    null, "E00" + i, "员工" + i, 1, "ID_CARD", "11010119900101" + String.format("%04d", i), null,
                    "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                    LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);
            compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 3, 1),
                    new BigDecimal("8000"), "初始", List.of()));
        }
        periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
    }

    // ------------------------------------------------------------------ 预校验

    @Test
    void 预校验不写核算数据但持久化批次与错误() throws Exception {
        int recordsBefore = countRecords();
        int itemsBefore = countItems();

        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}, {"E002", "员工2", 800}}),
                periodId, "FIXED", null);

        assertThat(result.batchId()).isPositive();
        assertThat(result.status()).isEqualTo("VALIDATED");
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successRows()).isEqualTo(2);
        assertThat(result.failRows()).isZero();
        assertThat(result.errors()).isEmpty();

        // 预校验只读核算数据
        assertThat(countRecords()).isEqualTo(recordsBefore);
        assertThat(countItems()).isEqualTo(itemsBefore);

        // 批次、错误与原始文件持久化，刷新后可按 batchId 回捞
        assertThat(importService.get(result.batchId()).totalRows()).isEqualTo(2);
        String filePath = jdbc.queryForObject(
                "SELECT file_path FROM pay_import_batch WHERE id = ?", String.class, result.batchId());
        assertThat(Files.exists(java.nio.file.Path.of(filePath))).isTrue();
    }

    @Test
    void 同一文件二次上传返回409() throws Exception {
        byte[] bytes = buildFile("FIXED",
                new Object[][]{{"E001", "员工1", 1500}});
        MockMultipartFile first = new MockMultipartFile("file", "a.xlsx", XLSX, bytes);
        MockMultipartFile second = new MockMultipartFile("file", "b.xlsx", XLSX, bytes);

        importService.validate(first, periodId, "FIXED", null);
        assertThatThrownBy(() -> importService.validate(second, periodId, "FIXED", null))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void 幂等唯一键包含业务类型() {
        String sql = """
                INSERT INTO pay_import_batch
                  (period_id, biz_type, file_name, file_hash, import_mode,
                   total_rows, success_rows, fail_rows, status, operator_id, created_at)
                VALUES (?, ?, 'a.xlsx', 'samehash', 'UPSERT', 0, 0, 0, 'VALIDATED', 1, NOW())
                """;
        jdbc.update(sql, periodId, "FIXED");
        assertThatCode(() -> jdbc.update(sql, periodId, "VARIABLE")).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update(sql, periodId, "FIXED"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void 失败行禁止提交且不产生新记录() throws Exception {
        int recordsBefore = countRecords();

        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{
                        {"E001", "员工1", 1500},
                        {"E999", "不存在", 100},
                        {"E002", "员工2", -5}}),
                periodId, "FIXED", null);

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.failRows()).isEqualTo(2);
        assertThat(result.errors()).extracting(e -> e.errorCode())
                .contains("NO_RECORD", "AMOUNT_NEGATIVE");
        assertThat(result.errors()).extracting(e -> e.rowNo()).contains(4, 5);

        assertThatThrownBy(() -> importService.commit(result.batchId()))
                .isInstanceOf(ImportConflictException.class)
                .satisfies(e -> assertThat(((ImportConflictException) e).getFailRows()).isEqualTo(2));

        // 导入不创建核算记录；失败的批次一行都没有写入
        assertThat(countRecords()).isEqualTo(recordsBefore);
        assertThat(itemAmount("E001", "POST_ALLOW")).isNull();
    }

    // ------------------------------------------------------------------ 提交

    @Test
    void 提交只改已有记录_FIXED默认覆盖本期金额() throws Exception {
        Long recordId = recordId("E001");
        insertExistingAllowance(recordId, "300.00");
        int recordsBefore = countRecords();

        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}}), periodId, "FIXED", "UPSERT");
        ImportCommitResultDTO committed = importService.commit(result.batchId());

        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.successRows()).isEqualTo(1);
        assertThat(committed.failRows()).isZero();

        // 覆盖而非累加
        assertThat(itemAmount("E001", "POST_ALLOW")).isEqualByComparingTo("1500.00");
        assertThat(countRecords()).isEqualTo(recordsBefore);

        // 记录合计随之重算
        BigDecimal gross = jdbc.queryForObject(
                "SELECT gross_amount FROM pay_record WHERE id = ?", BigDecimal.class, recordId);
        assertThat(gross).isEqualByComparingTo("10100.00"); // 8000 + 600 工龄 + 1500 岗位津贴

        assertThat(jdbc.queryForObject(
                "SELECT status FROM pay_import_batch WHERE id = ?", String.class, result.batchId()))
                .isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_audit_log WHERE biz_type = 'IMPORT' AND biz_id = ? AND action = 'UPDATE'",
                Integer.class, result.batchId())).isPositive();

        // 重复提交被拒
        assertThatThrownBy(() -> importService.commit(result.batchId()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void APPEND模式已下线_INSERT_ONLY不覆盖已有明细() throws Exception {
        // APPEND 对绝对金额累加 == 重复发薪，设计 P0-5 要求 MVP 删除
        assertThatThrownBy(() -> importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}}), periodId, "FIXED", "APPEND"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);

        Long recordId = recordId("E001");
        insertExistingAllowance(recordId, "300.00");

        ImportValidateResultDTO insertOnly = importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 9999}}), periodId, "FIXED", "INSERT_ONLY");
        importService.commit(insertOnly.batchId());
        assertThat(itemAmount("E001", "POST_ALLOW")).isEqualByComparingTo("300.00");
    }

    @Test
    void 提交时重新校验_期间锁定后409() throws Exception {
        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}}), periodId, "FIXED", null);

        // 预校验之后期间被别人锁定
        periodService.lock(periodId, 2L);

        assertThatThrownBy(() -> importService.commit(result.batchId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已锁定");
    }

    @Test
    void DEPT_SUB不能导入其他部门的记录() throws Exception {
        jdbc.update("INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status) VALUES (10, 1, 1, 'D010', '研发组', ',1,', 10, 1)");
        // 让当前用户变成部门 10 的 DEPT_SUB 账号：E001 在部门 1，不在其子树
        jdbc.update("UPDATE sys_user SET dept_id = 10 WHERE id = 1");
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:import:submit", "payroll:record:view", "payroll:export"),
                "DEPT_SUB", null));

        assertThatThrownBy(() -> importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}}), periodId, "FIXED", null))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void DEPT范围可以导入本部门记录() throws Exception {
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:import:submit", "payroll:record:view"), "DEPT", null));

        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}}), periodId, "FIXED", null);
        assertThat(result.failRows()).isZero();
    }

    @Test
    void 部门级账号不能读取他人发起的批次() throws Exception {
        // 用户 2 发起批次
        CurrentUser.set(new CurrentUser(2L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:import:submit", "payroll:record:view", "payroll:export"),
                "COMPANY", null));
        ImportValidateResultDTO batch = importService.validate(
                fixedFile(new Object[][]{{"E999", "不存在", 100}}), periodId, "FIXED", null);

        // 部门级账号连 read 都不行：错误明细里带别部门的工号与原值
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:import:submit", "payroll:record:view", "payroll:export"),
                "DEPT_SUB", null));
        assertThatThrownBy(() -> importService.get(batch.batchId()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> importService.errors(batch.batchId()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void 提交重校验失败会刷新错误表与failRows() throws Exception {
        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E001", "员工1", 1500}}), periodId, "FIXED", null);
        // 预览之后记录被删除（模拟并发解锁删行 / 数据被改）
        jdbc.update("DELETE FROM pay_record WHERE id = ?", recordId("E001"));

        assertThatThrownBy(() -> importService.commit(result.batchId()))
                .isInstanceOf(ImportConflictException.class)
                .satisfies(e -> assertThat(((ImportConflictException) e).getFailRows()).isEqualTo(1));

        // 前端轮询必须能看到最新的失败行与原因
        assertThat(jdbc.queryForObject(
                "SELECT fail_rows FROM pay_import_batch WHERE id = ?", Integer.class, result.batchId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_import_error WHERE batch_id = ? AND error_code = 'NO_RECORD'",
                Integer.class, result.batchId())).isEqualTo(1);
    }

    @Test
    void 校验失败不留下孤儿原始文件() throws Exception {
        java.nio.file.Path storage = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "payroll-test-import");
        long before = Files.exists(storage)
                ? Files.list(storage).count() : 0;

        byte[] bad = buildFileRaw(List.of("工号", "不存在的科目"),
                List.of("empNo", "NOPE"), new Object[][]{{"E001", 1}});
        assertThatThrownBy(() -> importService.validate(
                new MockMultipartFile("file", "bad.xlsx", XLSX, bad), periodId, "FIXED", null))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);

        long after = Files.exists(storage) ? Files.list(storage).count() : 0;
        assertThat(after).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 模板与错误报告

    @Test
    void 模板列由工资项字典动态生成() throws Exception {
        PreparedExport template = importService.template(periodId, "FIXED");
        try (InputStream in = Files.newInputStream(template.path());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> titles = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            sheet.getRow(0).forEach(c -> titles.add(c.getStringCellValue()));
            sheet.getRow(1).forEach(c -> keys.add(c.getStringCellValue()));

            assertThat(titles).contains("工号", "姓名", "岗位津贴");
            // 非 FIXED 的变动项不出现在固定项模板里
            assertThat(titles).doesNotContain("绩效奖金");
            assertThat(keys).contains("empNo", "empName", "POST_ALLOW");
        } finally {
            Files.deleteIfExists(template.path());
        }
    }

    @Test
    void 错误报告可下载且写审计() throws Exception {
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:import:submit", "payroll:export", "payroll:record:view"), "ALL", null));

        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E999", "不存在", 100}}), periodId, "FIXED", null);
        assertThat(result.failRows()).isEqualTo(1);

        PreparedExport report = importService.errors(result.batchId());
        try (InputStream in = Files.newInputStream(report.path());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            Row header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("行号");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("错误代码");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(report.path());
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_audit_log WHERE biz_type = 'EXPORT' AND biz_id = ?",
                Integer.class, result.batchId())).isPositive();
    }

    @Test
    void 无导出权限不能下载错误报告() throws Exception {
        ImportValidateResultDTO result = importService.validate(
                fixedFile(new Object[][]{{"E999", "不存在", 100}}), periodId, "FIXED", null);
        CurrentUser.set(new CurrentUser(1L, TestDb.COMPANY_DEFAULT,
                Set.of("payroll:import:submit"), "ALL", null));
        assertThatThrownBy(() -> importService.errors(result.batchId()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void 缺工号列时提示缺工号而不是别的列() throws Exception {
        byte[] bad = buildFileRaw(List.of("姓名", "基本工资"),
                List.of("empName", "BASE_SALARY"), new Object[][]{{"员工1", 1}});
        assertThatThrownBy(() -> importService.validate(
                new MockMultipartFile("file", "no-empno.xlsx", XLSX, bad), periodId, "FIXED", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工号");
    }

    @Test
    void 未知工号列为列不匹配时报400() throws Exception {
        byte[] bytes = buildFileRaw(List.of("工号", "姓名", "不存在的科目"),
                List.of("empNo", "empName", "NOPE"), new Object[][]{{"E001", "员工1", 1}});
        assertThatThrownBy(() -> importService.validate(
                new MockMultipartFile("file", "x.xlsx", XLSX, bytes), periodId, "FIXED", null))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);
    }

    // ------------------------------------------------------------------ 工具

    private MockMultipartFile fixedFile(Object[][] rows) throws Exception {
        return new MockMultipartFile("file", "change.xlsx", XLSX,
                buildFile("FIXED", rows));
    }

    private byte[] buildFile(String bizType, Object[][] rows) throws Exception {
        List<String> titles = List.of("工号", "姓名", "岗位津贴");
        List<String> keys = List.of("empNo", "empName", "POST_ALLOW");
        return buildFileRaw(titles, keys, rows);
    }

    private byte[] buildFileRaw(List<String> titles, List<String> keys, Object[][] rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("data");
            Row titleRow = sheet.createRow(0);
            Row keyRow = sheet.createRow(1);
            for (int i = 0; i < titles.size(); i++) {
                titleRow.createCell(i).setCellValue(titles.get(i));
                keyRow.createCell(i).setCellValue(keys.get(i));
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 2);
                for (int c = 0; c < rows[r].length; c++) {
                    Object value = rows[r][c];
                    if (value instanceof Number number) {
                        row.createCell(c).setCellValue(number.doubleValue());
                    } else if (value != null) {
                        row.createCell(c).setCellValue(value.toString());
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** POST_ALLOW 在字典里 editable=0、importable=1，编制台改不了，只能直接造一条已有明细。 */
    private void insertExistingAllowance(Long recordId, String amount) {
        jdbc.update("""
                INSERT INTO pay_record_item
                  (record_id, item_code, item_name, category, direction, amount, source, sort_no)
                VALUES (?, 'POST_ALLOW', '岗位津贴', 'FIXED', 1, ?, 'MANUAL', 20)
                """, recordId, new BigDecimal(amount));
    }

    private Long recordId(String empNo) {
        return jdbc.queryForObject(
                "SELECT id FROM pay_record WHERE period_id = ? AND emp_no = ?", Long.class, periodId, empNo);
    }

    private BigDecimal itemAmount(String empNo, String itemCode) {
        List<BigDecimal> amounts = jdbc.queryForList("""
                SELECT i.amount
                  FROM pay_record_item i
                  JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ? AND r.emp_no = ? AND i.item_code = ?
                """, BigDecimal.class, periodId, empNo, itemCode);
        return amounts.isEmpty() ? null : amounts.get(0);
    }

    private int countRecords() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pay_record WHERE period_id = ?",
                Integer.class, periodId);
    }

    private int countItems() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_record_item i
                  JOIN pay_record r ON r.id = i.record_id
                 WHERE r.period_id = ?
                """, Integer.class, periodId);
    }
}
