package com.insbean.payroll.record;

import com.insbean.payroll.domain.entity.PayItemEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.record.dto.GridColumn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工资表列集合 = 启用科目 ∪ 本期明细里出现过的科目。
 *
 * <p>为什么不能只用"当前启用科目"：科目在生成之后被停用时，明细里还留着它，
 * 按当前字典算列会让前端求和与合计对不上；导出同理，必须按"实际有数据的列"出表。
 * 停用列照样出现，只是标记为不可编辑。
 */
@Component
public class ColumnSnapshotFactory {

    private static final int DEFAULT_SCALE = 2;

    private final ItemService itemService;

    public ColumnSnapshotFactory(ItemService itemService) {
        this.itemService = itemService;
    }

    public List<GridColumn> build(Long companyId, Collection<PayRecordItemEntity> items) {
        List<GridColumn> enabled = itemService.listEnabled(companyId).stream()
                .map(d -> new GridColumn(d.itemCode(), d.itemName(), d.category(),
                        d.direction(), d.precisionScale(), d.editable()))
                .toList();

        Set<String> known = enabled.stream().map(GridColumn::itemCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> used = items.stream()
                .map(PayRecordItemEntity::getItemCode)
                .filter(code -> code != null && !known.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (used.isEmpty()) {
            return enabled;
        }

        Map<String, PayItemEntity> dictionary = itemService.list(companyId, null, null).stream()
                .collect(Collectors.toMap(PayItemDefDTO::itemCode, ColumnSnapshotFactory::toEntity, (a, b) -> a));

        List<GridColumn> merged = new ArrayList<>(enabled);
        for (String code : used) {
            PayItemEntity def = dictionary.get(code);
            merged.add(new GridColumn(
                    code,
                    def == null ? code : def.getItemName(),
                    def == null ? "VARIABLE" : def.getCategory(),
                    def == null ? 1 : def.getDirection(),
                    def == null || def.getPrecisionScale() == null ? DEFAULT_SCALE : def.getPrecisionScale(),
                    false));
        }
        return merged;
    }

    private static PayItemEntity toEntity(PayItemDefDTO dto) {
        PayItemEntity entity = new PayItemEntity();
        entity.setItemCode(dto.itemCode());
        entity.setItemName(dto.itemName());
        entity.setCategory(dto.category());
        entity.setDirection(dto.direction());
        entity.setPrecisionScale(dto.precisionScale());
        return entity;
    }
}
