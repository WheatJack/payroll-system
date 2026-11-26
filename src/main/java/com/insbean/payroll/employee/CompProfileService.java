package com.insbean.payroll.employee;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpCompProfileEntity;
import com.insbean.payroll.domain.entity.EmpCompProfileItemEntity;
import com.insbean.payroll.employee.dto.CompProfileDTO;
import com.insbean.payroll.employee.dto.CompProfileItemDTO;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.mapper.CompProfileItemMapper;
import com.insbean.payroll.employee.mapper.CompProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 薪酬档案：按生效区间版本化（详细设计 §4.1）。
 *
 * <p>核心不变量：同一员工任意一天最多命中一段档案，且区间首尾相接不留空洞。
 * 每次保存都是"插入一段 + 截断被它盖住的那一段"，而不是覆盖原记录——
 * 这样已经锁定的历史工资表永远不会被追溯改写。
 */
@Service
public class CompProfileService {

    private static final int STORE_SCALE = 4;
    private static final int DISPLAY_SCALE = 2;

    private final CompProfileMapper profileMapper;
    private final CompProfileItemMapper itemMapper;

    public CompProfileService(CompProfileMapper profileMapper, CompProfileItemMapper itemMapper) {
        this.profileMapper = profileMapper;
        this.itemMapper = itemMapper;
    }

    public List<CompProfileDTO> timeline(Long employeeId) {
        List<EmpCompProfileEntity> profiles = loadTimeline(employeeId);
        Map<Long, List<CompProfileItemDTO>> items = loadItems(profiles.stream()
                .map(EmpCompProfileEntity::getId).toList());
        return profiles.stream()
                .map(p -> toDto(p, items.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** 取某日生效的档案，无档案返回 null（而不是抛异常）。Task 7 生成时用它取薪酬。 */
    public CompProfileDTO effectiveAt(Long employeeId, LocalDate date) {
        EmpCompProfileEntity entity = profileMapper.effectiveAt(employeeId, date);
        if (entity == null) {
            return null;
        }
        return toDto(entity, loadItems(List.of(entity.getId())).getOrDefault(entity.getId(), List.of()));
    }

    @Transactional
    public CompProfileDTO create(CompProfileSaveReq req) {
        if (req.employeeId() == null) {
            throw new BizException(400, "employeeId 必填");
        }
        if (req.effectiveFrom() == null) {
            throw new BizException(400, "生效日期必填");
        }
        if (req.baseSalary() == null) {
            throw new BizException(400, "基本工资必填");
        }
        return save(req);
    }

    @Transactional
    public CompProfileDTO update(Long id, CompProfileSaveReq req) {
        EmpCompProfileEntity old = profileMapper.selectById(id);
        if (old == null) {
            throw new BizException(404, "薪酬档案不存在");
        }
        return save(new CompProfileSaveReq(
                id,
                old.getEmployeeId(),
                req.effectiveFrom() != null ? req.effectiveFrom() : old.getEffectiveFrom(),
                req.baseSalary() != null ? req.baseSalary() : old.getBaseSalary(),
                req.changeReason() != null ? req.changeReason() : old.getChangeReason(),
                req.items() != null ? req.items() : List.of()));
    }

    /**
     * 从 effectiveFrom 起写入一段新档案，并把被它覆盖的旧区间截断到前一天。
     * 同一天重复保存视为"替换当天这段"，不新增记录。
     */
    @Transactional
    public CompProfileDTO save(CompProfileSaveReq req) {
        if (req.employeeId() == null) {
            throw new BizException(400, "employeeId 必填");
        }
        if (req.effectiveFrom() == null) {
            throw new BizException(400, "生效日期必填");
        }
        if (req.baseSalary() == null) {
            throw new BizException(400, "基本工资必填");
        }
        if (req.baseSalary().signum() < 0) {
            throw new BizException(400, "基本工资不能为负数");
        }

        LocalDate from = req.effectiveFrom();
        List<EmpCompProfileEntity> timeline = loadTimeline(req.employeeId());

        // 1) 同一天已有档案 → 就地替换，id 保持不变
        EmpCompProfileEntity sameDay = timeline.stream()
                .filter(p -> p.getEffectiveFrom().equals(from))
                .findFirst()
                .orElse(null);
        if (sameDay != null) {
            sameDay.setBaseSalary(store(req.baseSalary()));
            sameDay.setChangeReason(req.changeReason());
            sameDay.setStatus(1);
            profileMapper.updateById(sameDay);
            replaceItems(sameDay.getId(), req.items());
            assertNoOverlap(req.employeeId());
            return toDto(sameDay, normalizeItems(req.items()));
        }

        // 2) 截断被新区间覆盖的旧段，新区间继承它的右端，保证不留空洞
        LocalDate newTo;
        EmpCompProfileEntity covering = timeline.stream()
                .filter(p -> p.getEffectiveFrom().isBefore(from))
                .filter(p -> p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(from))
                .findFirst()
                .orElse(null);
        if (covering != null) {
            newTo = covering.getEffectiveTo();
            covering.setEffectiveTo(from.minusDays(1));
            profileMapper.updateById(covering);
        } else {
            newTo = timeline.stream()
                    .map(EmpCompProfileEntity::getEffectiveFrom)
                    .filter(d -> d.isAfter(from))
                    .min(LocalDate::compareTo)
                    .map(d -> d.minusDays(1))
                    .orElse(null);
        }

        EmpCompProfileEntity entity = new EmpCompProfileEntity();
        entity.setEmployeeId(req.employeeId());
        entity.setEffectiveFrom(from);
        entity.setEffectiveTo(newTo);
        entity.setBaseSalary(store(req.baseSalary()));
        entity.setChangeReason(req.changeReason());
        entity.setStatus(1);
        entity.setDeleted(0);
        profileMapper.insert(entity);
        replaceItems(entity.getId(), req.items());

        assertNoOverlap(req.employeeId());
        return toDto(entity, normalizeItems(req.items()));
    }

    private List<EmpCompProfileEntity> loadTimeline(Long employeeId) {
        return profileMapper.selectList(Wrappers.<EmpCompProfileEntity>lambdaQuery()
                .eq(EmpCompProfileEntity::getEmployeeId, employeeId)
                .eq(EmpCompProfileEntity::getStatus, 1)
                .orderByAsc(EmpCompProfileEntity::getEffectiveFrom));
    }

    private Map<Long, List<CompProfileItemDTO>> loadItems(List<Long> profileIds) {
        if (profileIds.isEmpty()) {
            return Map.of();
        }
        return itemMapper.selectList(Wrappers.<EmpCompProfileItemEntity>lambdaQuery()
                        .in(EmpCompProfileItemEntity::getProfileId, profileIds)
                        .orderByAsc(EmpCompProfileItemEntity::getItemCode))
                .stream()
                .collect(Collectors.groupingBy(EmpCompProfileItemEntity::getProfileId,
                        LinkedHashMap::new,
                        Collectors.mapping(i -> new CompProfileItemDTO(i.getItemCode(), display(i.getAmount())),
                                Collectors.toList())));
    }

    private void replaceItems(Long profileId, List<CompProfileItemDTO> items) {
        itemMapper.delete(Wrappers.<EmpCompProfileItemEntity>lambdaQuery()
                .eq(EmpCompProfileItemEntity::getProfileId, profileId));
        for (CompProfileItemDTO item : normalizeItems(items)) {
            EmpCompProfileItemEntity entity = new EmpCompProfileItemEntity();
            entity.setProfileId(profileId);
            entity.setItemCode(item.itemCode());
            entity.setAmount(store(item.amount()));
            itemMapper.insert(entity);
        }
    }

    /** 应用层区间校验：数据库只有一个 (employee_id, effective_from) 唯一键，挡不住重叠。 */
    private void assertNoOverlap(Long employeeId) {
        List<EmpCompProfileEntity> timeline = loadTimeline(employeeId);
        for (int i = 0; i < timeline.size() - 1; i++) {
            LocalDate to = timeline.get(i).getEffectiveTo();
            LocalDate nextFrom = timeline.get(i + 1).getEffectiveFrom();
            if (to == null || !to.isBefore(nextFrom)) {
                throw new BizException(409, "薪酬档案区间重叠，请检查生效日期");
            }
        }
    }

    private static List<CompProfileItemDTO> normalizeItems(List<CompProfileItemDTO> items) {
        if (items == null) {
            return List.of();
        }
        List<CompProfileItemDTO> result = new ArrayList<>();
        for (CompProfileItemDTO item : items) {
            if (item == null || item.itemCode() == null || item.itemCode().isBlank()) {
                throw new BizException(400, "固定津贴的科目编码必填");
            }
            if (item.amount() == null || item.amount().signum() < 0) {
                throw new BizException(400, "固定津贴金额必须为非负数：" + item.itemCode());
            }
            result.add(new CompProfileItemDTO(item.itemCode(), display(item.amount())));
        }
        return result;
    }

    private static CompProfileDTO toDto(EmpCompProfileEntity e, List<CompProfileItemDTO> items) {
        return new CompProfileDTO(
                e.getId(), e.getEmployeeId(), e.getEffectiveFrom(), e.getEffectiveTo(),
                display(e.getBaseSalary()), e.getChangeReason(), e.getStatus(), items);
    }

    private static BigDecimal store(BigDecimal value) {
        return value.setScale(STORE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal display(BigDecimal value) {
        return value == null ? null : value.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP);
    }
}
