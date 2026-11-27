package com.insbean.payroll.record;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.VersionConflictException;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.record.dto.ChangesetChange;
import com.insbean.payroll.record.dto.ChangesetItem;
import com.insbean.payroll.record.dto.ChangesetReq;
import com.insbean.payroll.record.dto.ChangesetResult;
import com.insbean.payroll.record.dto.GridColumn;
import com.insbean.payroll.record.dto.GridResponse;
import com.insbean.payroll.record.dto.GridRow;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordServiceTest extends AbstractDbTest {

    private static final long COMPILER = 1L;
    private static final long OTHER_USER = 2L;

    @Autowired
    private RecordService recordService;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long periodId;
    private Long recordId;

    @BeforeEach
    void setUpPayroll() {
        Long empId = employeeService.create(new EmployeeSaveReq(
                null, "E001", "张三", 1, "ID_CARD", "110101199001011234", null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 3, 1),
                new BigDecimal("8000"), "入职定薪", List.of()));

        periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, COMPILER);

        recordId = jdbc.queryForObject(
                "SELECT id FROM pay_record WHERE period_id = ? AND emp_no = 'E001'", Long.class, periodId);
    }

    @Test
    void 保存后应发与扣款分别等于对应方向的明细加总() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "2000.00")));
        recordService.applyChangeset(periodId, oneChange(recordId, 1, Map.of("ATT_DEDUCT", "300.00")));

        GridResponse grid = recordService.grid(periodId);
        GridRow row = grid.rows().get(0);
        Map<String, Integer> dir = grid.columns().stream()
                .collect(Collectors.toMap(GridColumn::itemCode, GridColumn::direction));

        // 明细恒非负，必须按 direction 分别汇总；直接对全部 items 求和会把扣款项算进应发
        BigDecimal gross = sumByDirection(row, dir, 1);
        BigDecimal deduction = sumByDirection(row, dir, 2);

        assertThat(new BigDecimal(row.grossAmount())).isEqualByComparingTo(gross);
        assertThat(new BigDecimal(row.deductionAmount())).isEqualByComparingTo(deduction);
        assertThat(new BigDecimal(row.netAmount())).isEqualByComparingTo(gross.subtract(deduction));

        assertThat(gross).isEqualByComparingTo("10600.00");        // 8000 + 600 工龄 + 2000 绩效
        assertThat(deduction).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(row.netAmount())).isEqualByComparingTo("10300.00");
    }

    @Test
    void 版本冲突返回409且不覆盖他人修改() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "1.00")));

        assertThatThrownBy(() -> recordService.applyChangeset(
                periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "2.00"))))
                .isInstanceOf(VersionConflictException.class);

        // 冲突时整体回滚：依然是先提交者的 1.00
        assertThat(itemAmount(recordId, "PERF_BONUS")).isEqualByComparingTo("1.00");
    }

    @Test
    void 冲突异常带出全部冲突行以便前端高亮() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "1.00")));

        try {
            recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "2.00")));
        } catch (VersionConflictException e) {
            assertThat(e.getRecordIds()).containsExactly(recordId);
            return;
        }
        throw new AssertionError("应当抛出 VersionConflictException");
    }

    @Test
    void 锁定期间拒绝保存() {
        periodService.lock(periodId, OTHER_USER);

        assertThatThrownBy(() -> recordService.applyChangeset(
                periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "1.00"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已锁定");
        assertThat(itemAmountOrNull(recordId, "PERF_BONUS")).isNull();
    }

    @Test
    void 保存成功后版本号加一并返回新合计() {
        ChangesetResult result = recordService.applyChangeset(
                periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "2000.00")));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).version()).isEqualTo(1);
        assertThat(result.rows().get(0).grossAmount()).isEqualTo("10600.00");
    }

    @Test
    void 金额精度不因浮点误差丢分() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0,
                Map.of("PERF_BONUS", "0.10", "OTHER_BONUS", "0.20")));

        GridRow row = recordService.grid(periodId).rows().get(0);

        assertThat(row.items().get("PERF_BONUS")).isEqualTo("0.10");
        assertThat(row.items().get("OTHER_BONUS")).isEqualTo("0.20");
        // 8000.00 + 600.00 + 0.10 + 0.20 = 8600.30，恰好，不能出现 8600.300000000001
        assertThat(row.grossAmount()).isEqualTo("8600.30");
    }

    @Test
    void grid结构符合契约且金额是两位小数字符串() {
        GridResponse grid = recordService.grid(periodId);

        assertThat(grid.total()).isEqualTo(grid.rows().size()).isEqualTo(1);
        assertThat(grid.period().id()).isEqualTo(periodId);
        assertThat(grid.period().periodYm()).isEqualTo("2026-11");
        assertThat(grid.period().status()).isEqualTo("EDITING");
        assertThat(grid.columns()).extracting(GridColumn::itemCode).contains("BASE_SALARY", "PERF_BONUS");

        GridRow row = grid.rows().get(0);
        assertThat(row.recordId()).isEqualTo(recordId);
        assertThat(row.version()).isZero();
        assertThat(row.empNo()).isEqualTo("E001");
        assertThat(row.items().get("BASE_SALARY")).isEqualTo("8000.00");
        assertThat(row.items().get("SENIORITY_PAY")).isEqualTo("600.00");
        assertThat(row.grossAmount()).isEqualTo("8600.00");
        assertThat(row.deductionAmount()).isEqualTo("0.00");
        assertThat(row.seniorityYears()).isEqualTo("6.00");
    }

    @Test
    void 不许手工修改计算项() {
        assertThatThrownBy(() -> recordService.applyChangeset(
                periodId, oneChange(recordId, 0, Map.of("BASE_SALARY", "9999.00"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许手工编辑");
    }

    @Test
    void 不属于本期间的记录被拒绝() {
        assertThatThrownBy(() -> recordService.applyChangeset(
                periodId, oneChange(999999L, 0, Map.of("PERF_BONUS", "1.00"))))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 四位小数科目不会让整批保存失败() {
        jdbc.update("""
                INSERT INTO pay_item (company_id, item_code, item_name, category, direction, data_type,
                                      precision_scale, include_in_gross, editable, importable, sort_no, status)
                VALUES (0, 'HOURLY_ADJ', '小时调整', 'VARIABLE', 1, 'AMOUNT', 4, 1, 1, 1, 60, 1)
                """);

        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("HOURLY_ADJ", "1.2345")));

        GridRow row = recordService.grid(periodId).rows().get(0);
        assertThat(row.items().get("HOURLY_ADJ")).isEqualTo("1.2345");
        // 8600.00 + 1.2345 = 8601.2345，不能被强行抹成两位小数
        assertThat(row.grossAmount()).isEqualTo("8601.2345");
    }

    @Test
    void 停用科目仍然出现在行里以保证合计对得上() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "100.00")));
        jdbc.update("UPDATE pay_item SET status = 0 WHERE item_code = 'PERF_BONUS'");

        GridResponse grid = recordService.grid(periodId);

        assertThat(grid.columns()).extracting(GridColumn::itemCode).contains("PERF_BONUS");
        assertThat(grid.columns()).filteredOn(c -> "PERF_BONUS".equals(c.itemCode())).singleElement()
                .satisfies(c -> assertThat(c.editable()).isFalse());
        assertThat(grid.rows().get(0).grossAmount()).isEqualTo("8700.00");
    }

    @Test
    void 保存后期间合计被同步刷新() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "2000.00")));

        assertThat(periodService.get(periodId).totalGross()).isEqualByComparingTo("10600.00");
    }

    // ------------------------------------------------------------------

    private static BigDecimal sumByDirection(GridRow row, Map<String, Integer> dir, int direction) {
        return row.items().entrySet().stream()
                .filter(e -> dir.getOrDefault(e.getKey(), 0) == direction)
                .map(e -> new BigDecimal(e.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void 缺少items的changeset返回400且不写数据() {
        int versionBefore = jdbc.queryForObject(
                "SELECT version_no FROM pay_record WHERE id = ?", Integer.class, recordId);

        assertThatThrownBy(() -> recordService.applyChangeset(periodId, new ChangesetReq(List.of(
                new ChangesetChange(recordId, versionBefore, List.of())))))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> recordService.applyChangeset(periodId, new ChangesetReq(List.of(
                new ChangesetChange(recordId, versionBefore, null)))))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);

        // 不能"200 成功但实际没写"：版本号必须保持原值
        assertThat(jdbc.queryForObject(
                "SELECT version_no FROM pay_record WHERE id = ?", Integer.class, recordId))
                .isEqualTo(versionBefore);
    }

    @Test
    void 锁定与解锁同步记录状态() {
        periodService.lock(periodId, OTHER_USER);
        assertThat(recordStatus(recordId)).isEqualTo("CONFIRMED");

        periodService.unlock(periodId, OTHER_USER, "复核后释放");
        assertThat(recordStatus(recordId)).isEqualTo("DRAFT");
    }

    private String recordStatus(Long id) {
        return jdbc.queryForObject("SELECT record_status FROM pay_record WHERE id = ?", String.class, id);
    }

    @Test
    void 停用科目服务端拒绝写入() {
        jdbc.update("UPDATE pay_item SET status = 0 WHERE item_code = 'PERF_BONUS'");

        assertThatThrownBy(() -> recordService.applyChangeset(
                periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "1.00"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void 超出DECIMAL18_4的金额返回400而不是500() {
        assertThatThrownBy(() -> recordService.applyChangeset(
                periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "999999999999999.9999"))))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);
        // 事务内没有写入任何东西
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_record_item WHERE record_id = ? AND item_code = 'PERF_BONUS'
                """, Integer.class, recordId)).isZero();
    }

    @Test
    void 不计入应发的收入项不改合计但保留明细() {
        jdbc.update("""
                INSERT INTO pay_item (company_id, item_code, item_name, category, direction,
                                      data_type, precision_scale, include_in_gross, editable,
                                      importable, sort_no, status)
                VALUES (0, 'NON_GROSS', '不计应发补贴', 'VARIABLE', 1, 'AMOUNT', 2, 0, 1, 1, 58, 1)
                """);
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("NON_GROSS", "500.00")));

        GridRow row = recordService.grid(periodId).rows().get(0);
        assertThat(row.items()).containsEntry("NON_GROSS", "500.00");
        assertThat(new BigDecimal(row.grossAmount())).isEqualByComparingTo("8600.00");
        assertThat(new BigDecimal(row.netAmount())).isEqualByComparingTo("8600.00");
    }

    @Test
    void 金额变更与查看都写审计() {
        recordService.applyChangeset(periodId, oneChange(recordId, 0, Map.of("PERF_BONUS", "2000.00")));

        Map<String, Object> created = jdbc.queryForMap("""
                SELECT action, old_value, new_value FROM pay_audit_log
                 WHERE biz_type = 'RECORD' AND biz_id = ? AND field_name = 'PERF_BONUS'
                 ORDER BY id DESC LIMIT 1
                """, recordId);
        assertThat(created.get("action")).isEqualTo("UPDATE");
        assertThat(created.get("old_value")).isNull();
        assertThat(created.get("new_value")).isEqualTo("2000.00");

        recordService.applyChangeset(periodId, oneChange(recordId, 1, Map.of("PERF_BONUS", "2500.00")));
        Map<String, Object> changed = jdbc.queryForMap("""
                SELECT old_value, new_value FROM pay_audit_log
                 WHERE biz_type = 'RECORD' AND biz_id = ? AND field_name = 'PERF_BONUS'
                 ORDER BY id DESC LIMIT 1
                """, recordId);
        assertThat(changed.get("old_value")).isEqualTo("2000.00");
        assertThat(changed.get("new_value")).isEqualTo("2500.00");

        recordService.grid(periodId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_audit_log
                 WHERE biz_type = 'RECORD' AND action = 'VIEW' AND field_name = 'grid' AND biz_id = ?
                """, Integer.class, periodId)).isEqualTo(1);
    }

    private static ChangesetReq oneChange(Long recordId, int version, Map<String, String> items) {
        return new ChangesetReq(List.of(new ChangesetChange(recordId, version,
                items.entrySet().stream()
                        .map(e -> new ChangesetItem(e.getKey(), e.getValue()))
                        .toList())));
    }

    private BigDecimal itemAmount(Long recordId, String itemCode) {
        return jdbc.queryForObject(
                "SELECT amount FROM pay_record_item WHERE record_id = ? AND item_code = ?",
                BigDecimal.class, recordId, itemCode);
    }

    private BigDecimal itemAmountOrNull(Long recordId, String itemCode) {
        List<BigDecimal> rows = jdbc.queryForList(
                "SELECT amount FROM pay_record_item WHERE record_id = ? AND item_code = ?",
                BigDecimal.class, recordId, itemCode);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
