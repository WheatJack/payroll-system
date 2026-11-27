package com.insbean.payroll.item;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.payroll.PayCategory;
import com.insbean.payroll.domain.payroll.PayDirection;
import com.insbean.payroll.domain.payroll.PayItemDef;
import com.insbean.payroll.item.dto.PayItemCreateReq;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.item.dto.PayItemUpdateReq;
import com.insbean.payroll.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemServiceTest extends AbstractDbTest {

    @Autowired
    private ItemService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 科目字典转换为领域对象时保留方向与类别() {
        Map<String, PayItemDef> defs = service.toDomainDefs(1L);

        assertThat(defs.get("ATT_DEDUCT").direction()).isEqualTo(PayDirection.DECREASE);
        assertThat(defs.get("SENIORITY_PAY").category()).isEqualTo(PayCategory.COMPUTED);
        assertThat(defs.get("BASE_SALARY").direction()).isEqualTo(PayDirection.INCREASE);
        assertThat(defs.get("BASE_SALARY").category()).isEqualTo(PayCategory.FIXED);
    }

    @Test
    void 精度必须在0到4之间() {
        assertThatThrownBy(() -> service.create(new PayItemCreateReq(
                null, "P_BAD", "坏精度", "VARIABLE", 1, "AMOUNT", 5,
                true, true, true, 0, 1), 1L))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.create(new PayItemCreateReq(
                null, "P_BAD2", "坏精度", "VARIABLE", 1, "AMOUNT", -1,
                true, true, true, 0, 1), 1L))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);

        // 0 是合法精度（取整）
        assertThat(service.create(new PayItemCreateReq(
                null, "P_ZERO", "整数科目", "VARIABLE", 1, "AMOUNT", 0,
                true, true, true, 0, 1), 1L).precisionScale()).isZero();
    }

    @Test
    void 同一编码的主体专属科目优先且不重复() {
        jdbc.update("""
                INSERT INTO pay_item (company_id, item_code, item_name, category, direction, data_type,
                                      precision_scale, include_in_gross, editable, importable, sort_no, status)
                VALUES (1, 'PERF_BONUS', '绩效奖金(本公司)', 'VARIABLE', 1, 'AMOUNT', 4, 1, 1, 1, 40, 1)
                """);

        List<PayItemDefDTO> own = service.list(1L, null, null);
        assertThat(own).filteredOn(d -> d.itemCode().equals("PERF_BONUS"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.companyId()).isEqualTo(1L);
                    assertThat(d.itemName()).isEqualTo("绩效奖金(本公司)");
                    assertThat(d.precisionScale()).isEqualTo(4);
                });
        assertThat(service.requireItem(1L, "PERF_BONUS").getCompanyId()).isEqualTo(1L);
        // 其它主体仍然回退到集团通用定义
        assertThat(service.requireItem(2L, "PERF_BONUS").getCompanyId()).isZero();
    }

    @Test
    void 已被使用的科目不能修改计入应发开关() {
        jdbc.update("""
                INSERT INTO pay_record_item
                  (record_id, item_code, item_name, category, direction, amount, source, sort_no)
                VALUES (1, 'PERF_BONUS', '绩效奖金', 'VARIABLE', 1, 100, 'MANUAL', 40)
                """);

        assertThatThrownBy(() -> service.update(4L, new PayItemUpdateReq(
                null, null, null, null, null, false, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void 合并集团通用科目与主体专属科目() {
        // 服务端只给北京分公司配了一个专属科目
        jdbc.update("""
                INSERT INTO pay_item (company_id, item_code, item_name, category, direction, data_type,
                                      precision_scale, include_in_gross, editable, importable, sort_no, status)
                VALUES (2, 'BJ_MEAL', '北京餐补', 'FIXED', 1, 'AMOUNT', 2, 1, 0, 1, 25, 1)
                """);

        List<PayItemDefDTO> beijing = service.listEnabled(2L);
        List<PayItemDefDTO> shanghai = service.listEnabled(3L);

        assertThat(beijing).extracting(PayItemDefDTO::itemCode).contains("BJ_MEAL", "BASE_SALARY");
        assertThat(shanghai).extracting(PayItemDefDTO::itemCode).doesNotContain("BJ_MEAL").contains("BASE_SALARY");
        // 按 sortNo 升序：BJ_MEAL(25) 落在 POST_ALLOW(20) 与 SENIORITY_PAY(30) 之间
        List<String> codes = beijing.stream().map(PayItemDefDTO::itemCode).toList();
        assertThat(codes.indexOf("BJ_MEAL")).isEqualTo(codes.indexOf("POST_ALLOW") + 1);
    }

    @Test
    void 停用科目不出现在领域定义中() {
        jdbc.update("UPDATE pay_item SET status = 0 WHERE item_code = 'PERF_BONUS'");

        assertThat(service.toDomainDefs(1L)).doesNotContainKey("PERF_BONUS");
        assertThat(service.listEnabled(1L)).extracting(PayItemDefDTO::itemCode).doesNotContain("PERF_BONUS");
    }

    @Test
    void 新增科目时主体缺省取传入的默认主体() {
        PayItemDefDTO created = service.create(new PayItemCreateReq(
                null, "SH_BUS", "上海交通补贴", "FIXED", 1, "AMOUNT", 2,
                true, false, true, 26, 1), 3L);

        assertThat(created.companyId()).isEqualTo(3L);
        assertThat(created.direction()).isEqualTo(1);
        assertThat(service.listEnabled(3L)).extracting(PayItemDefDTO::itemCode).contains("SH_BUS");
    }

    @Test
    void 已被工资表使用的科目禁止修改方向与类别() {
        jdbc.update("""
                INSERT INTO pay_record_item (record_id, item_code, item_name, category, direction, amount, source, sort_no)
                VALUES (999, 'PERF_BONUS', '绩效奖金', 'VARIABLE', 1, 0, 'MANUAL', 40)
                """);

        assertThatThrownBy(() -> service.update(4L, new PayItemUpdateReq(
                null, "VARIABLE", 2, null, null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("方向");
    }

    @Test
    void 金额字段以两位小数字符串下发() {
        PayItemDefDTO dto = service.listEnabled(1L).stream()
                .filter(d -> "BASE_SALARY".equals(d.itemCode()))
                .findFirst().orElseThrow();

        assertThat(dto.editable()).isFalse();
        assertThat(dto.includeInGross()).isTrue();
        assertThat(dto.precisionScale()).isEqualTo(2);
        assertThat(new BigDecimal("0.10").add(new BigDecimal("0.20"))).isEqualByComparingTo("0.30");
    }
}
