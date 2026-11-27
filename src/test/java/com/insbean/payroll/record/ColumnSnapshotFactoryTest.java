package com.insbean.payroll.record;

import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.record.dto.GridColumn;
import com.insbean.payroll.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停用科目也要按字典里的 precision_scale 出列。
 *
 * <p>导出/编制台的列快照如果在停用列上写死 2 位，4 位科目的历史明细
 * 会在前端显示与实际金额不一致。
 */
class ColumnSnapshotFactoryTest extends AbstractDbTest {

    @Autowired
    private ColumnSnapshotFactory factory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 停用科目列保留原精度且不可编辑() {
        jdbc.update("""
                INSERT INTO pay_item (company_id, item_code, item_name, category, direction,
                                      data_type, precision_scale, include_in_gross, editable,
                                      importable, sort_no, status)
                VALUES (0, 'PRECISE_OLD', '高精度旧科目', 'VARIABLE', 1, 'AMOUNT', 4, 1, 1, 1, 100, 0)
                """);

        PayRecordItemEntity item = new PayRecordItemEntity();
        item.setItemCode("PRECISE_OLD");
        item.setAmount(new BigDecimal("1.2345"));

        List<GridColumn> columns = factory.build(1L, List.of(item));

        assertThat(columns).filteredOn(c -> c.itemCode().equals("PRECISE_OLD"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.scale()).isEqualTo(4);
                    assertThat(c.editable()).isFalse();
                    assertThat(c.itemName()).isEqualTo("高精度旧科目");
                });
    }
}
