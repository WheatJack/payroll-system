package com.insbean.payroll.importjob;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.importjob.dto.ImportCommitResultDTO;
import com.insbean.payroll.importjob.dto.ImportValidateResultDTO;
import com.insbean.payroll.report.PreparedExport;
import com.insbean.payroll.security.CryptoService;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人员批量导入：100 行全量成功、证件号可解密、错误行拒绝、同文件重复提交 409。
 */
class EmployeeImportServiceTest extends AbstractDbTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private EmployeeImportService service;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 一百行合法文件全量导入且证件号可解密() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rows.add(new String[]{
                    "IMP" + String.format("%04d", i), "批量员工" + i, "男", "REGULAR", "ID_CARD",
                    idNo(i), "13800138000", "D001", "P6", "工程师", "2025-01-01", "2018-07-01", "8000.00"});
        }
        MockMultipartFile file = xlsx(rows);

        ImportValidateResultDTO validated = service.validate(file);
        assertThat(validated.status()).isEqualTo("VALIDATED");
        assertThat(validated.totalRows()).isEqualTo(100);
        assertThat(validated.successRows()).isEqualTo(100);
        assertThat(validated.failRows()).isZero();

        ImportCommitResultDTO committed = service.commit(validated.batchId());
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.successRows()).isEqualTo(100);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emp_employee", Integer.class))
                .isEqualTo(100);
        // 基本工资列同时生成了薪酬档案，生效日 = 入职日
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emp_comp_profile", Integer.class))
                .isEqualTo(100);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM emp_comp_profile p JOIN emp_employee e ON e.id = p.employee_id
                 WHERE e.emp_no = 'IMP0001' AND p.effective_from = '2025-01-01'
                """, Integer.class)).isEqualTo(1);

        // 证件号走的是与单人建档同一条加密路径：库里是密文，能解回原文
        byte[] cipher = jdbc.queryForObject(
                "SELECT id_no_cipher FROM emp_employee WHERE emp_no = 'IMP0001'", byte[].class);
        assertThat(cryptoService.decrypt(cipher)).isEqualTo(idNo(1));
        assertThat(new String(cipher)).doesNotContain(idNo(1));
    }

    @Test
    void 含三行错误的文件被拒且错误报告准确() throws Exception {
        List<String[]> rows = List.<String[]>of(
                new String[]{"E001", "甲", "男", "REGULAR", "ID_CARD", "110101199001010001",
                        null, "D001", null, null, "2025-01-01", null, "8000"},
                new String[]{"E001", "乙", "女", "REGULAR", "ID_CARD", "110101199001010002",
                        null, "D001", null, null, "2025-01-01", null, "8000"},
                new String[]{"E003", "丙", "男", "REGULAR", "ID_CARD", "110101199001010003",
                        null, "NOPE", null, null, "2025-01-01", null, "8000"},
                new String[]{"E004", "丁", "男", "REGULAR", "ID_CARD", "110101199001010004",
                        null, "D001", null, null, "2025-01-01", null, "abc"});

        ImportValidateResultDTO validated = service.validate(xlsx(rows));

        assertThat(validated.failRows()).isEqualTo(3);
        assertThat(validated.errors()).extracting(e -> e.errorCode())
                .contains("DUPLICATE_IN_FILE", "DEPT_NOT_FOUND", "AMOUNT_INVALID");
        assertThat(validated.errors()).extracting(e -> e.rowNo()).containsExactlyInAnyOrder(3, 4, 5);

        assertThatThrownBy(() -> service.commit(validated.batchId()))
                .isInstanceOf(ImportConflictException.class)
                .satisfies(e -> assertThat(((ImportConflictException) e).getFailRows()).isEqualTo(3));
        // failRows>0 时一行都不能落库
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emp_employee", Integer.class)).isZero();
    }

    @Test
    void 同一文件重复上传返回409() throws Exception {
        List<String[]> rows = List.<String[]>of(new String[]{"E001", "甲", "男", "REGULAR", "ID_CARD",
                "110101199001010001", null, "D001", null, null, "2025-01-01", null, "8000"});
        byte[] bytes = toBytes(rows);
        service.validate(new MockMultipartFile("file", "a.xlsx", XLSX, bytes));

        assertThatThrownBy(() -> service.validate(
                new MockMultipartFile("file", "b.xlsx", XLSX, bytes)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void 模板列头固定十三列() throws Exception {
        PreparedExport template = service.template();
        try (InputStream in = Files.newInputStream(template.path());
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            List<String> headers = new ArrayList<>();
            workbook.getSheetAt(0).getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
            assertThat(headers).containsExactlyElementsOf(EmployeeImportValidator.HEADERS);
        } finally {
            Files.deleteIfExists(template.path());
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String idNo(int seq) {
        return "11010119900101" + String.format("%04d", seq);
    }

    private MockMultipartFile xlsx(List<String[]> rows) throws Exception {
        return new MockMultipartFile("file", "employees.xlsx", XLSX, toBytes(rows));
    }

    private static byte[] toBytes(List<String[]> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("employees");
            Row header = sheet.createRow(0);
            for (int i = 0; i < EmployeeImportValidator.HEADERS.size(); i++) {
                header.createCell(i).setCellValue(EmployeeImportValidator.HEADERS.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                String[] values = rows.get(r);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] != null) {
                        row.createCell(c).setCellValue(values[c]);
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
