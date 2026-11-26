package com.insbean.payroll.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工作日计算。
 *
 * <p><b>为什么必须用工作日而不是自然日</b>：计薪天数参数 21.75 的定义是
 * {@code (365 − 104) ÷ 12}，104 就是全年 52 周 × 2 天周末——它本身就是**工作日**口径。
 * 拿自然日做分子、工作日做分母属于量纲错误，入职当月能差出好几百块
 * （例：2026-11-16 入职，11 个工作日 vs 15 个自然日）。
 *
 * <p>法定节假日本期允许为空（M1 不维护节假日日历），但接口留出来了：
 * 配 {@code payroll.calendar.holidays} 为逗号分隔的 ISO 日期即可生效，
 * 例：{@code PAYROLL_HOLIDAYS=2026-10-01,2026-10-02,2026-10-03}。
 */
@Component
public class WorkingDays {

    private final Set<LocalDate> holidays;

    public WorkingDays(@Value("${payroll.calendar.holidays:}") String holidays) {
        this.holidays = parse(holidays);
    }

    /** 计算 [start, end] 闭区间内的工作日天数（排除周六、周日与已配置的法定节假日）。 */
    public int between(LocalDate start, LocalDate end) {
        return between(start, end, Set.of());
    }

    public int between(LocalDate start, LocalDate end, Set<LocalDate> extraHolidays) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (isWorkingDay(day, extraHolidays)) {
                count++;
            }
        }
        return count;
    }

    public boolean isWorkingDay(LocalDate day) {
        return isWorkingDay(day, Set.of());
    }

    private boolean isWorkingDay(LocalDate day, Set<LocalDate> extraHolidays) {
        DayOfWeek dow = day.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(day) && !extraHolidays.contains(day);
    }

    public Set<LocalDate> holidays() {
        return Set.copyOf(holidays);
    }

    private static Set<LocalDate> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<LocalDate> parsed = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> parsed.add(LocalDate.parse(s)));
        return Set.copyOf(parsed);
    }
}
