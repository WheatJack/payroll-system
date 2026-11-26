package com.insbean.payroll.item;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.PayItemEntity;
import com.insbean.payroll.domain.payroll.PayCategory;
import com.insbean.payroll.domain.payroll.PayDirection;
import com.insbean.payroll.domain.payroll.PayItemDef;
import com.insbean.payroll.item.dto.PayItemCreateReq;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.item.dto.PayItemUpdateReq;
import com.insbean.payroll.item.mapper.PayItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ItemService {

    /** 集团通用科目的 company_id。 */
    public static final long GLOBAL_COMPANY = 0L;

    private final PayItemMapper itemMapper;

    public ItemService(PayItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    /** 合并集团通用科目与本主体专属科目，仅启用项，按 sortNo 升序。 */
    public List<PayItemDefDTO> listEnabled(Long companyId) {
        return list(companyId, null, 1);
    }

    public List<PayItemDefDTO> list(Long companyId, String category, Integer status) {
        Long scope = companyId == null ? GLOBAL_COMPANY : companyId;
        var query = Wrappers.<PayItemEntity>lambdaQuery()
                .in(PayItemEntity::getCompanyId, GLOBAL_COMPANY, scope);
        if (category != null && !category.isBlank()) {
            query.eq(PayItemEntity::getCategory, category);
        }
        if (status != null) {
            query.eq(PayItemEntity::getStatus, status);
        }
        query.orderByAsc(PayItemEntity::getSortNo).orderByAsc(PayItemEntity::getId);
        return itemMapper.selectList(query).stream().map(ItemService::toDto).toList();
    }

    /**
     * 转换为计算引擎的领域定义 —— Task 7 直接喂给 {@code PayrollCalculator}。
     * 停用科目不会出现在这里，计算引擎因而不会为它生成明细。
     */
    public Map<String, PayItemDef> toDomainDefs(Long companyId) {
        Map<String, PayItemDef> defs = new LinkedHashMap<>();
        for (PayItemDefDTO dto : listEnabled(companyId)) {
            defs.put(dto.itemCode(), new PayItemDef(
                    dto.itemCode(),
                    dto.itemName(),
                    PayCategory.valueOf(dto.category()),
                    dto.direction() != null && dto.direction() == 2 ? PayDirection.DECREASE : PayDirection.INCREASE));
        }
        return defs;
    }

    @Transactional
    public PayItemDefDTO create(PayItemCreateReq req, Long defaultCompanyId) {
        PayItemEntity entity = new PayItemEntity();
        entity.setCompanyId(req.companyId() != null ? req.companyId() : defaultCompanyId);
        entity.setItemCode(req.itemCode());
        entity.setItemName(req.itemName());
        entity.setCategory(req.category());
        entity.setDirection(req.direction() == null ? 1 : req.direction());
        entity.setDataType(req.dataType() == null ? "AMOUNT" : req.dataType());
        entity.setPrecisionScale(req.precisionScale() == null ? 2 : req.precisionScale());
        entity.setIncludeInGross(booleanFlag(req.includeInGross(), true));
        entity.setEditable(booleanFlag(req.editable(), true));
        entity.setImportable(booleanFlag(req.importable(), true));
        entity.setSortNo(req.sortNo() == null ? 0 : req.sortNo());
        entity.setStatus(req.status() == null ? 1 : req.status());
        entity.setDeleted(0);
        try {
            itemMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "科目编码已存在：" + req.itemCode());
        }
        return toDto(entity);
    }

    @Transactional
    public PayItemDefDTO update(Long id, PayItemUpdateReq req) {
        PayItemEntity entity = itemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "工资项不存在");
        }
        if (req.category() != null && !req.category().equals(entity.getCategory())) {
            requireUnused(entity.getItemCode(), "类别");
            entity.setCategory(req.category());
        }
        if (req.direction() != null && !req.direction().equals(entity.getDirection())) {
            requireUnused(entity.getItemCode(), "方向");
            entity.setDirection(req.direction());
        }
        if (req.itemName() != null) {
            entity.setItemName(req.itemName());
        }
        if (req.dataType() != null) {
            entity.setDataType(req.dataType());
        }
        if (req.precisionScale() != null) {
            entity.setPrecisionScale(req.precisionScale());
        }
        if (req.includeInGross() != null) {
            entity.setIncludeInGross(booleanFlag(req.includeInGross(), true));
        }
        if (req.editable() != null) {
            entity.setEditable(booleanFlag(req.editable(), true));
        }
        if (req.importable() != null) {
            entity.setImportable(booleanFlag(req.importable(), true));
        }
        if (req.sortNo() != null) {
            entity.setSortNo(req.sortNo());
        }
        if (req.status() != null) {
            entity.setStatus(req.status());
        }
        itemMapper.updateById(entity);
        return toDto(entity);
    }

    public PayItemEntity requireItem(Long companyId, String itemCode) {
        PayItemEntity entity = itemMapper.selectOne(Wrappers.<PayItemEntity>lambdaQuery()
                .in(PayItemEntity::getCompanyId, GLOBAL_COMPANY, companyId == null ? GLOBAL_COMPANY : companyId)
                .eq(PayItemEntity::getItemCode, itemCode)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new BizException(400, "工资项不存在：" + itemCode);
        }
        return entity;
    }

    private void requireUnused(String itemCode, String field) {
        if (itemMapper.countUsedRecords(itemCode) > 0) {
            throw new BizException(409, "该科目已被工资表使用，不能修改" + field + "：" + itemCode);
        }
    }

    private static Integer booleanFlag(Boolean value, boolean defaultValue) {
        boolean resolved = value == null ? defaultValue : value;
        return resolved ? 1 : 0;
    }

    private static Boolean flag(Integer value) {
        return value != null && value == 1;
    }

    private static PayItemDefDTO toDto(PayItemEntity e) {
        return new PayItemDefDTO(
                e.getId(),
                e.getCompanyId(),
                e.getItemCode(),
                e.getItemName(),
                e.getCategory(),
                e.getDirection(),
                e.getDataType(),
                e.getPrecisionScale(),
                flag(e.getIncludeInGross()),
                flag(e.getEditable()),
                flag(e.getImportable()),
                e.getSortNo(),
                e.getStatus());
    }
}
