package com.insbean.payroll.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingDaysTest {

    private final WorkingDays workingDays = new WorkingDays("");

    @Test
    void 入职当月按工作日而不是自然日折算() {
        // 2026-11-16(周一) 至 2026-11-30(周一)：15 个自然日 / 11 个工作日
        assertThat(workingDays.between(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 30)))
                .isEqualTo(11);

        // 8000 / 21.75 * 11 = 4045.977... → 4045.98
        // 若误用自然日会得到 8000 / 21.75 * 15 = 5517.24，量纲错误
        assertThat(workingDays.between(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 30)))
                .isNotEqualTo(15);
    }

    @Test
    void 整月工作日符合月平均计薪天数口径() {
        // 2026-11 共 30 天，周末 9 天（1、7、8、14、15、21、22、28、29）→ 21 个工作日
        assertThat(workingDays.between(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
                .isEqualTo(21);
    }

    @Test
    void 单日与边界() {
        assertThat(workingDays.between(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 16))).isEqualTo(1);
        // 周六
        assertThat(workingDays.between(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 21))).isZero();
        // 结束早于开始
        assertThat(workingDays.between(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 16))).isZero();
        assertThat(workingDays.between(null, LocalDate.of(2026, 11, 16))).isZero();
    }

    @Test
    void 法定节假日被排除且列表可配置() {
        WorkingDays withHoliday = new WorkingDays("2026-11-16,2026-11-17");

        assertThat(withHoliday.between(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 30)))
                .isEqualTo(9);
        assertThat(withHoliday.holidays()).containsExactlyInAnyOrder(
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17));
    }

    @Test
    void 空配置表示本期不维护节假日日历() {
        assertThat(new WorkingDays("").holidays()).isEmpty();
        assertThat(new WorkingDays(null).holidays()).isEmpty();
        assertThat(new WorkingDays("  ").holidays()).isEmpty();
    }
}
