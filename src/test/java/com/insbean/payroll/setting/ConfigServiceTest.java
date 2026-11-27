package com.insbean.payroll.setting;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.setting.dto.ConfigItemDTO;
import com.insbean.payroll.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 系统参数：读取、在线修改、格式校验与只读保护。 */
class ConfigServiceTest extends AbstractDbTest {

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 列表返回全部业务参数() {
        List<ConfigItemDTO> configs = configService.list();
        assertThat(configs).extracting(ConfigItemDTO::key).contains(
                ConfigService.MONTHLY_PAYABLE_DAYS,
                ConfigService.SENIORITY_PAY_PER_YEAR,
                ConfigService.CALENDAR_HOLIDAYS);
        assertThat(configs).filteredOn(c -> c.key().equals(ConfigService.MONTHLY_PAYABLE_DAYS))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.value()).isEqualTo("21.75");
                    assertThat(c.valueType()).isEqualTo("DECIMAL");
                    assertThat(c.editable()).isTrue();
                });
        assertThat(configs).filteredOn(c -> c.key().equals(ConfigService.CALENDAR_HOLIDAYS))
                .singleElement()
                .satisfies(c -> assertThat(c.warning()).contains("节假日未配置"));
    }

    @Test
    void 修改后立即生效并写审计() {
        assertThat(configService.getDecimal(ConfigService.SENIORITY_PAY_PER_YEAR, BigDecimal.ZERO))
                .isEqualByComparingTo("100");

        configService.update(ConfigService.SENIORITY_PAY_PER_YEAR, "200");

        // 不依赖本地缓存：下一次读取就是新值
        assertThat(configService.getDecimal(ConfigService.SENIORITY_PAY_PER_YEAR, BigDecimal.ZERO))
                .isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_audit_log
                 WHERE biz_type = 'CONFIG' AND action = 'UPDATE' AND field_name = 'payroll.seniority-pay-per-year'
                """, Integer.class)).isPositive();
    }

    @Test
    void 节假日列表可解析且格式非法返回400() {
        configService.update(ConfigService.CALENDAR_HOLIDAYS, "2026-11-02,2026-11-03");
        assertThat(configService.list()).filteredOn(c -> c.key().equals(ConfigService.CALENDAR_HOLIDAYS))
                .singleElement()
                .satisfies(c -> assertThat(c.warning()).isNull());
        assertThat(configService.getDateList(ConfigService.CALENDAR_HOLIDAYS))
                .containsExactlyInAnyOrder(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3));

        assertThatThrownBy(() -> configService.update(ConfigService.CALENDAR_HOLIDAYS, "2026-13-01"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(400);
    }

    @Test
    void 数值参数格式与范围非法返回400() {
        assertThatThrownBy(() -> configService.update(ConfigService.SENIORITY_PAY_PER_YEAR, "abc"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> configService.update(ConfigService.MONTHLY_PAYABLE_DAYS, "0"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> configService.update(ConfigService.MONTHLY_PAYABLE_DAYS, "32"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> configService.update("payroll.unknown", "1"))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);
    }

    @Test
    void 只读参数不可修改() {
        assertThatThrownBy(() -> configService.update("payroll.system-version", "2"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        assertThat(configService.getString("payroll.system-version", null)).isEqualTo("1");
    }
}
