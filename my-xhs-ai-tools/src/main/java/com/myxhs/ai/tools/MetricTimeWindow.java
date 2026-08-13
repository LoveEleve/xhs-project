package com.myxhs.ai.tools;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 指标时间窗解析（共享）：yyyy-MM-dd~yyyy-MM-dd → [from 00:00, to+1day 00:00) 半开区间。
 * 校验：格式、先后、跨度上限（≤maxDays 天）。
 */
public final class MetricTimeWindow {

    private MetricTimeWindow() {
    }

    public static LocalDateTime[] parse(String window, int maxDays) {
        if (window == null || !window.contains("~")) {
            throw new IllegalArgumentException("时间窗格式应为 yyyy-MM-dd~yyyy-MM-dd，实际: " + window);
        }
        String[] parts = window.split("~");
        if (parts.length != 2) {
            throw new IllegalArgumentException("时间窗格式应为 yyyy-MM-dd~yyyy-MM-dd，实际: " + window);
        }
        LocalDate from = LocalDate.parse(parts[0].trim());
        LocalDate to = LocalDate.parse(parts[1].trim());
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期: " + window);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw new IllegalArgumentException("时间窗超过上限 " + maxDays + " 天: " + window);
        }
        return new LocalDateTime[]{from.atStartOfDay(), to.plusDays(1).atStartOfDay()};
    }
}
