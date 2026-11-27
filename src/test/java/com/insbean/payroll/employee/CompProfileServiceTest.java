package com.insbean.payroll.employee;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.employee.dto.CompProfileDTO;
import com.insbean.payroll.employee.dto.CompProfileItemDTO;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompProfileServiceTest extends AbstractDbTest {

    @Autowired
    private CompProfileService service;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long empId;

    @BeforeEach
    void setUpEmployee() {
        empId = employeeService.create(new EmployeeSaveReq(
                null, "E001", "张三", 1, "ID_CARD", "110101199001011234", null,
                "REGULAR", null, null, TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);

        // 现状：2025-07-01 起基本工资 8000，至今有效
        service.save(req(empId, LocalDate.of(2025, 7, 1), "8000", "入职定薪"));
    }

    @Test
    void 调薪后旧区间被自动截断且不重叠() {
        service.save(req(empId, LocalDate.of(2026, 12, 1), "9500", "年度调薪"));

        List<CompProfileDTO> all = service.timeline(empId);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).effectiveTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(all.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(all.get(1).effectiveTo()).isNull();
        assertThat(all.get(1).baseSalary()).isEqualByComparingTo("9500");
    }

    @Test
    void 查询某日生效的档案() {
        assertThat(service.effectiveAt(empId, LocalDate.of(2026, 11, 30)).baseSalary())
                .isEqualByComparingTo("8000");

        service.save(req(empId, LocalDate.of(2026, 12, 1), "9500", "年度调薪"));

        assertThat(service.effectiveAt(empId, LocalDate.of(2026, 11, 30)).baseSalary())
                .isEqualByComparingTo("8000");
        assertThat(service.effectiveAt(empId, LocalDate.of(2026, 12, 1)).baseSalary())
                .isEqualByComparingTo("9500");
    }

    @Test
    void 无档案时返回空而不是抛异常() {
        assertThat(service.effectiveAt(999L, LocalDate.of(2026, 11, 30))).isNull();
    }

    @Test
    void 同一天再次保存是替换而不是新增() {
        service.save(req(empId, LocalDate.of(2025, 7, 1), "8600", "更正"));

        List<CompProfileDTO> all = service.timeline(empId);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).baseSalary()).isEqualByComparingTo("8600");
    }

    @Test
    void 连续多次调薪后区间互不重叠且首尾相接() {
        service.save(req(empId, LocalDate.of(2026, 1, 1), "8500", "一季度调薪"));
        service.save(req(empId, LocalDate.of(2026, 7, 1), "9000", "年中调薪"));

        List<CompProfileDTO> all = service.timeline(empId);

        assertThat(all).hasSize(3);
        assertThat(all.get(0).effectiveFrom()).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(all.get(0).effectiveTo()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(all.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(all.get(1).effectiveTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(all.get(2).effectiveFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(all.get(2).effectiveTo()).isNull();
    }

    @Test
    void 固定津贴随档案版本化() {
        service.save(new CompProfileSaveReq(null, empId, LocalDate.of(2026, 12, 1),
                new BigDecimal("9500"), "年度调薪",
                List.of(new CompProfileItemDTO("POST_ALLOW", new BigDecimal("800")))));

        List<CompProfileDTO> all = service.timeline(empId);

        assertThat(all.get(0).items()).isEmpty();
        assertThat(all.get(1).items()).hasSize(1);
        assertThat(all.get(1).items().get(0).itemCode()).isEqualTo("POST_ALLOW");
        assertThat(all.get(1).items().get(0).amount()).isEqualByComparingTo("800");
    }

    @Test
    void 负数基本工资被拒绝() {
        assertThatThrownBy(() -> service.save(req(empId, LocalDate.of(2026, 12, 1), "-1", "错误数据")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("基本工资");
    }

    @Test
    void 金额输出统一两位小数() {
        CompProfileDTO dto = service.effectiveAt(empId, LocalDate.of(2026, 1, 1));

        assertThat(dto.baseSalary().toPlainString()).isEqualTo("8000.00");
    }

    @Test
    void 同日重新保存作废旧段而不就地改金额() {
        Long oldId = service.timeline(empId).get(0).id();

        service.save(req(empId, LocalDate.of(2025, 7, 1), "8600", "更正"));

        assertThat(service.timeline(empId)).hasSize(1);
        // 旧行原值保留、只是被逻辑删除；不是被就地 UPDATE
        assertThat(jdbc.queryForObject(
                "SELECT base_salary FROM emp_comp_profile WHERE id = ?", BigDecimal.class, oldId))
                .isEqualByComparingTo("8000");
        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM emp_comp_profile WHERE id = ?", Integer.class, oldId))
                .isEqualTo(1);
    }

    @Test
    void 修改薪酬默认从次月一日起效而不是追溯() {
        Long id = service.timeline(empId).get(0).id();

        CompProfileDTO updated = service.update(id,
                new CompProfileSaveReq(null, null, null, new BigDecimal("9500"), "年度调薪", null));

        assertThat(updated.effectiveFrom()).isEqualTo(CompProfileService.nextMonthFirstDay());
        // 今天仍按旧档案发放，调薪不追溯
        assertThat(service.effectiveAt(empId, LocalDate.now()).baseSalary()).isEqualByComparingTo("8000");
    }

    @Test
    void 修改时未传items表示不改固定津贴() {
        service.save(new CompProfileSaveReq(null, empId, LocalDate.of(2025, 7, 1),
                new BigDecimal("8000"), "定薪",
                List.of(new CompProfileItemDTO("POST_ALLOW", new BigDecimal("500")))));
        Long id = service.timeline(empId).get(0).id();

        CompProfileDTO updated = service.update(id,
                new CompProfileSaveReq(null, null, null, new BigDecimal("9500"), "调薪", null));

        // items=null 是"不改"，不能把固定津贴清空
        assertThat(updated.items()).extracting(CompProfileItemDTO::itemCode).containsExactly("POST_ALLOW");
        assertThat(updated.items().get(0).amount()).isEqualByComparingTo("500");
    }

    @Test
    void 显式传空items才表示清空固定津贴() {
        service.save(new CompProfileSaveReq(null, empId, LocalDate.of(2025, 7, 1),
                new BigDecimal("8000"), "定薪",
                List.of(new CompProfileItemDTO("POST_ALLOW", new BigDecimal("500")))));
        Long id = service.timeline(empId).get(0).id();

        CompProfileDTO updated = service.update(id,
                new CompProfileSaveReq(null, null, null, new BigDecimal("9500"), "调薪", List.of()));

        assertThat(updated.items()).isEmpty();
    }

    @Test
    void 调薪写金额变更审计() {
        service.save(req(empId, LocalDate.of(2026, 12, 1), "9000", "年度调薪"));

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT old_value, new_value FROM pay_audit_log
                 WHERE biz_type = 'EMPLOYEE' AND biz_id = ? AND field_name = 'comp_profile'
                 ORDER BY id DESC LIMIT 1
                """, empId);
        assertThat(audit.get("old_value")).isEqualTo("8000.00");
        assertThat(audit.get("new_value")).isEqualTo("9000.00");
    }

    @Test
    void 同一天连续两次更正都要成功() {
        // setUp 已有 2025-07-01 的 8000 段：第一次更正会把它作废（deleted=1）
        service.save(req(empId, LocalDate.of(2025, 7, 1), "9000", "第一次更正"));
        // 第二次更正要再作废一个新段；旧唯一键 (employee, date, deleted) 在这里会撞键
        assertThatCode(() -> service.save(req(empId, LocalDate.of(2025, 7, 1), "9500", "第二次更正")))
                .doesNotThrowAnyException();

        List<CompProfileDTO> sameDay = service.timeline(empId).stream()
                .filter(p -> p.effectiveFrom().equals(LocalDate.of(2025, 7, 1)))
                .toList();
        assertThat(sameDay).hasSize(1);
        assertThat(sameDay.get(0).baseSalary()).isEqualByComparingTo("9500");
        assertThat(sameDay.get(0).status()).isEqualTo(1);
    }

    private static CompProfileSaveReq req(Long employeeId, LocalDate from, String salary, String reason) {
        return new CompProfileSaveReq(null, employeeId, from, new BigDecimal(salary), reason, List.of());
    }
}
