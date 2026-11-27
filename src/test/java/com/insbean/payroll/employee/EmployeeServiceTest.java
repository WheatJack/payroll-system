package com.insbean.payroll.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.dto.EmployeeDTO;
import com.insbean.payroll.employee.dto.EmployeeQuery;
import com.insbean.payroll.employee.dto.EmployeePageDTO;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeServiceTest extends AbstractDbTest {

    private static final String ID_NO = "110101199001011234";

    @Autowired
    private EmployeeService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 接口返回的人员不含证件号明文() throws Exception {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        EmployeeDTO dto = service.get(id);
        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).doesNotContain(ID_NO);
        assertThat(dto.idNoMask()).isEqualTo("****1234");
        assertThat(dto.mobileMask()).isEqualTo("138****8000");
    }

    @Test
    void 库里只存密文与盲索引不存明文() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id_no_cipher, id_no_hash, id_no_mask FROM emp_employee WHERE id = ?", id);

        byte[] cipher = (byte[]) row.get("id_no_cipher");
        assertThat(new String(cipher)).doesNotContain(ID_NO);
        assertThat(cipher).isNotEmpty();
        assertThat((String) row.get("id_no_hash")).hasSize(64);
        assertThat((String) row.get("id_no_mask")).isEqualTo("****1234");
    }

    @Test
    void 同一证件号不同写法也判定为重复() {
        service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        assertThatThrownBy(() -> service.create(createReq("E002", " 11010119900101123X ".replace('X', '4')), TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该证件号码已存在人员记录");
    }

    @Test
    void 工号重复被拒绝() {
        service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        assertThatThrownBy(() -> service.create(createReq("E001", "110101199001019999"), TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工号");
    }

    @Test
    void 新建人员同时落一条任职区间() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT company_id, dept_id, start_date, end_date FROM emp_employment WHERE employee_id = ?", id);
        assertThat(row.get("company_id")).isEqualTo(TestDb.COMPANY_DEFAULT);
        assertThat(row.get("end_date")).isNull();
    }

    @Test
    void 分页查询按关键字与状态过滤() {
        service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);
        service.create(createReq("E002", "110101199001019999"), TestDb.COMPANY_DEFAULT);

        EmployeePageDTO all = service.page(new EmployeeQuery(1, 20, null, null, null, null), TestDb.COMPANY_DEFAULT);
        EmployeePageDTO byKeyword = service.page(new EmployeeQuery(1, 20, "E002", null, null, null), TestDb.COMPANY_DEFAULT);

        assertThat(all.total()).isEqualTo(2);
        assertThat(byKeyword.records()).extracting(EmployeeDTO::empNo).containsExactly("E002");
        assertThat(byKeyword.records().get(0).idNoMask()).isEqualTo("****9999");
    }

    @Test
    void 缺少必填字段时给出中文提示() {
        assertThatThrownBy(() -> service.create(
                new EmployeeSaveReq(null, null, "张三", 1, "ID_CARD", null, null,
                        "REGULAR", null, null, TestDb.DEPT_DEFAULT, LocalDate.of(2020, 3, 1),
                        null, null, 1, null), TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工号");
    }

    @Test
    void 调动追加新区间而不改写历史() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        service.transfer(id, TestDb.COMPANY_BEIJING, TestDb.DEPT_BEIJING, LocalDate.of(2026, 12, 1));

        // 调动前的日期仍然归属原主体（历史工资表解锁重算不会串主体）
        assertThat(service.companyAt(id, LocalDate.of(2026, 11, 30))).isEqualTo(TestDb.COMPANY_DEFAULT);
        assertThat(service.companyAt(id, LocalDate.of(2026, 12, 1))).isEqualTo(TestDb.COMPANY_BEIJING);
        assertThat(service.companyAt(id, LocalDate.of(2027, 1, 1))).isEqualTo(TestDb.COMPANY_BEIJING);

        List<LocalDate> endDates = jdbc.queryForList(
                "SELECT end_date FROM emp_employment WHERE employee_id = ? ORDER BY start_date",
                LocalDate.class, id);
        List<String> changeTypes = jdbc.queryForList(
                "SELECT change_type FROM emp_employment WHERE employee_id = ? ORDER BY start_date",
                String.class, id);

        assertThat(endDates).hasSize(2);
        assertThat(endDates.get(0)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(endDates.get(1)).isNull();
        assertThat(changeTypes).containsExactly("HIRE", "TRANSFER");
    }

    @Test
    void 调动生效日不能早于当前区间起始日() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        assertThatThrownBy(() -> service.transfer(id, TestDb.COMPANY_BEIJING, TestDb.DEPT_BEIJING,
                LocalDate.of(2019, 1, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能早于");
    }

    @Test
    void 当天入职当天调部门也能落到新区间() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);
        LocalDate hireDate = LocalDate.of(2020, 3, 1);

        service.transfer(id, TestDb.COMPANY_BEIJING, TestDb.DEPT_BEIJING, hireDate);

        // 当天双区间并存：查归属按 start_date DESC 取最新，于是当天归新部门
        assertThat(service.companyAt(id, hireDate)).isEqualTo(TestDb.COMPANY_BEIJING);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM emp_employment WHERE employee_id = ?", Integer.class, id);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void 通过修改接口改部门也走追加区间() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        service.update(id, new EmployeeSaveReq(TestDb.COMPANY_BEIJING, null, null, null, null,
                null, null, null, null, null, TestDb.DEPT_BEIJING, null, null, null, null, null));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM emp_employment WHERE employee_id = ?", Integer.class, id);
        assertThat(rows).isEqualTo(2);
        assertThat(service.companyAt(id, LocalDate.now().minusDays(1))).isEqualTo(TestDb.COMPANY_DEFAULT);
    }

    @Test
    void 人员返回部门名与本单位工龄() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);

        EmployeeDTO dto = service.get(id);
        LocalDate today = LocalDate.now();
        assertThat(dto.deptName()).isEqualTo("综合部");
        assertThat(dto.seniorityYears())
                .isEqualTo(Period.between(LocalDate.of(2020, 3, 1), today).getYears() + ".00");
        assertThat(dto.socialSeniorityYears())
                .isEqualTo(Period.between(LocalDate.of(2018, 7, 1), today).getYears() + ".00");
    }

    @Test
    void 未填参加工作时间时社会工龄为空() {
        Long id = service.create(new EmployeeSaveReq(
                null, "E002", "李四", 1, "ID_CARD", "110101199001015678", null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                LocalDate.of(2024, 5, 20), null, null, 1, null), TestDb.COMPANY_DEFAULT);

        EmployeeDTO dto = service.get(id);
        assertThat(dto.socialSeniorityYears()).isNull();
        assertThat(dto.seniorityYears())
                .isEqualTo(Period.between(LocalDate.of(2024, 5, 20), LocalDate.now()).getYears() + ".00");
    }

    @Test
    void 新增人员写创建审计() {
        Long id = service.create(createReq("E001", ID_NO), TestDb.COMPANY_DEFAULT);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_audit_log
                 WHERE biz_type = 'EMPLOYEE' AND biz_id = ? AND action = 'CREATE'
                """, Integer.class, id)).isEqualTo(1);
    }

    @Test
    void 员工不能挂在其他主体的部门下() {
        assertThatThrownBy(() -> service.create(new EmployeeSaveReq(
                null, "E009", "张三", 1, "ID_CARD", "110101199001019999", null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_SHANGHAI,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("部门")
                .extracting("code").isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM emp_employee WHERE emp_no = 'E009'", Integer.class)).isZero();
    }

    @Test
    void 分页大小上限500且不静默截断() {
        for (int i = 0; i < 210; i++) {
            jdbc.update("""
                    INSERT INTO emp_employee
                      (company_id, emp_no, name, gender, id_type, id_no_cipher, id_no_hash,
                       id_no_hash_version, id_no_mask, employee_type, dept_id, hire_date, status, deleted)
                    VALUES (1, ?, ?, 1, 'ID_CARD', X'00', ?, 1, '****0000', 'REGULAR', 1,
                            '2020-01-01', 1, 0)
                    """, "BULK" + i, "批量员工" + i, String.format("%064d", i));
        }

        EmployeePageDTO page = service.page(
                new EmployeeQuery(1, 500, null, null, null, null), TestDb.COMPANY_DEFAULT);

        // 上限是 500 而不是 200：210 条必须全部返回
        assertThat(page.records()).hasSize(210);
        assertThat(page.size()).isEqualTo(500);
        assertThat(new EmployeeQuery(1, 501, null, null, null, null).pageSize()).isEqualTo(500);
    }

    private EmployeeSaveReq createReq(String empNo, String idNo) {
        return new EmployeeSaveReq(
                null, empNo, "张三", 1, "ID_CARD", idNo, "13800138000",
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1), LocalDate.of(2018, 7, 1), null, 1, null);
    }
}
