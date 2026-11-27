package com.insbean.payroll.setting;

import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.setting.dto.ConfigItemDTO;
import com.insbean.payroll.setting.mapper.ConfigMapper;
import com.insbean.payroll.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 系统参数（月计薪天数、工龄单价、法定节假日）。
 *
 * <p><b>不缓存</b>：单实例部署、参数只有个位数，每次查库的成本可以忽略；
 * 本地缓存只会在参数刚改完时给出旧值，并给将来多实例埋下不一致的坑。
 *
 * <p>Domain 层不依赖 Spring：调用方（GenerateService）在这里读值，
 * 再经 {@code EmployeePayContext} 传给 {@code PayrollCalculator}。
 */
@Service
public class ConfigService {

    public static final String MONTHLY_PAYABLE_DAYS = "payroll.monthly-payable-days";
    public static final String SENIORITY_PAY_PER_YEAR = "payroll.seniority-pay-per-year";
    public static final String CALENDAR_HOLIDAYS = "payroll.calendar.holidays";

    private final ConfigMapper configMapper;
    private final AuditService auditService;

    public ConfigService(ConfigMapper configMapper, AuditService auditService) {
        this.configMapper = configMapper;
        this.auditService = auditService;
    }

    public List<ConfigItemDTO> list() {
        return configMapper.selectList(null).stream()
                .map(ConfigService::toDto)
                .toList();
    }

    /** 读字符串；缺失或空白时回退调用方给的默认值。 */
    public String getString(String key, String defaultValue) {
        ConfigEntity entity = configMapper.findByKey(key);
        if (entity == null || entity.getConfigValue() == null || entity.getConfigValue().isBlank()) {
            return defaultValue;
        }
        return entity.getConfigValue().trim();
    }

    /** 读十进制；解析失败回退默认值（存储已校验，这里只是兜底）。 */
    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读日期列表；空值返回空集合（调用方自行决定是否回退环境变量）。 */
    public Set<LocalDate> getDateList(String key) {
        String raw = getString(key, "");
        return parseDates(raw);
    }

    /**
     * 更新参数。只读参数 403、格式非法 400、未知键 404——三条都在服务端拦，
     * 前端置灰只是体验优化。
     */
    @Transactional
    public ConfigItemDTO update(String key, String value) {
        ConfigEntity entity = configMapper.findByKey(key);
        if (entity == null) {
            // 契约：未知 key 返回 400
            throw new BizException(400, "系统参数不存在：" + key);
        }
        if (entity.getEditable() == null || entity.getEditable() != 1) {
            throw new BizException(403, "该参数为只读：" + key);
        }
        String normalized = normalize(entity, value);
        String oldValue = entity.getConfigValue();

        entity.setConfigValue(normalized);
        entity.setUpdatedBy(CurrentUser.get() == null ? null : CurrentUser.get().userId());
        entity.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(entity);

        auditService.record("CONFIG", entity.getId(), "UPDATE", key, oldValue, normalized);
        return toDto(entity);
    }

    private static String normalize(ConfigEntity entity, String value) {
        String raw = value == null ? "" : value.trim();
        String type = entity.getValueType() == null ? "TEXT" : entity.getValueType();
        switch (type) {
            case "DECIMAL" -> {
                BigDecimal decimal;
                try {
                    decimal = new BigDecimal(raw);
                } catch (NumberFormatException e) {
                    throw new BizException(400, "参数必须是十进制数：" + entity.getConfigKey());
                }
                if (decimal.signum() < 0) {
                    throw new BizException(400, "参数不能为负数：" + entity.getConfigKey());
                }
                if (MONTHLY_PAYABLE_DAYS.equals(entity.getConfigKey())
                        && (decimal.signum() <= 0 || decimal.compareTo(new BigDecimal("31")) > 0)) {
                    throw new BizException(400, "月计薪天数必须大于 0 且不超过 31");
                }
                return decimal.stripTrailingZeros().toPlainString();
            }
            case "INT" -> {
                try {
                    Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    throw new BizException(400, "参数必须是整数：" + entity.getConfigKey());
                }
                return raw;
            }
            case "DATE_LIST" -> {
                // 解析一次以校验格式，存储保持用户输入的规范形式
                buildDates(raw, true);
                return raw;
            }
            default -> {
                return raw;
            }
        }
    }

    private static Set<LocalDate> parseDates(String raw) {
        return buildDates(raw, false);
    }

    private static Set<LocalDate> buildDates(String raw, boolean strict) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return Set.copyOf(dates);
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                dates.add(LocalDate.parse(trimmed));
            } catch (RuntimeException e) {
                if (strict) {
                    throw new BizException(400, "节假日格式必须是逗号分隔的 YYYY-MM-DD：" + trimmed);
                }
            }
        }
        return Set.copyOf(dates);
    }

    private static ConfigItemDTO toDto(ConfigEntity e) {
        boolean valueEmpty = e.getConfigValue() == null || e.getConfigValue().isBlank();
        // 节假日为空不是"没配置完"，而是直接导致不足月折算多算钱：让前端能显示警示
        String warning = CALENDAR_HOLIDAYS.equals(e.getConfigKey()) && valueEmpty
                ? "法定节假日未配置：不足月折算只排除周末，折算天数可能偏多"
                : null;
        return new ConfigItemDTO(e.getConfigKey(), e.getConfigValue(), e.getValueType(),
                e.getDescription(), e.getEditable() != null && e.getEditable() == 1, warning);
    }
}
