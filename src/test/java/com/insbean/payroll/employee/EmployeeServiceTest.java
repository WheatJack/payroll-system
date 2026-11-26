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

    private EmployeeSaveReq createReq(String empNo, String idNo) {
        return new EmployeeSaveReq(
                null, empNo, "张三", 1, "ID_CARD", idNo, "13800138000",
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1), LocalDate.of(2018, 7, 1), null, 1, null);
    }
}
