package com.myxhs.ai.tools;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 指标时间窗值类（D4 基线确定性化）：窗口解析/校验/跨度/基线计算的**单一事实源**。
 * 供 PolicyGuard（app 策略层）与 BaselineWindowTool（工具层）共用——规则不得两处维护。
 * 校验：格式 yyyy-MM-dd~yyyy-MM-dd、start≤end、跨度≤31 天（与指标工具口径一致）。
 */
public record MetricWindow(LocalDate start, LocalDate end, long spanDays) {

    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})~(\\d{4}-\\d{2}-\\d{2})");
    public static final long MAX_WINDOW_DAYS = 31;

    /** 解析校验；非法抛 IllegalArgumentException（错误消息即用户可读原因） */
    public static MetricWindow parse(String window) {
        if (window == null || window.isBlank()) {
            throw new IllegalArgumentException("window 必填（yyyy-MM-dd~yyyy-MM-dd）");
        }
        var m = WINDOW_PATTERN.matcher(window.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("window 格式必须为 yyyy-MM-dd~yyyy-MM-dd");
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(m.group(1));
            end = LocalDate.parse(m.group(2));
        } catch (Exception e) {
            throw new IllegalArgumentException("window 日期非法");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("window 起始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("window 跨度不能超过 " + MAX_WINDOW_DAYS + " 天");
        }
        return new MetricWindow(start, end, span);
    }

    /** 上一同长窗口（同跨度、紧邻当前之前）：2026-08-01~2026-08-07 → 2026-07-25~2026-07-31 */
    public MetricWindow baseline() {
        return new MetricWindow(start.minusDays(spanDays), end.minusDays(spanDays), spanDays);
    }

    public String format() {
        return start + "~" + end;
    }
}
