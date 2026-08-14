package com.myxhs.ai.app.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户查询→当前时间窗的确定性解析（单一事实源，D4 基线确定性化）。
 * 供 AiQueryController（metric 路径）与 AgentHarness（注入当前窗口）共用——
 * 消除"最近 7 天"由模型自选的口径漂移。
 * 规则（Asia/Shanghai）：显式日期窗（两个日期）→ 单日期（当日）→ 上周 → 本周 → 昨天 → 今天 → 最近 7 天。
 */
public final class QueryWindowExtractor {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private QueryWindowExtractor() {
    }

    public static ZoneId zone() {
        return ZONE;
    }

    /** 永不为 null：无任何时间语义时默认最近 7 天 */
    public static String extract(String message) {
        LocalDate today = LocalDate.now(ZONE);
        if (message == null) {
            return today.minusDays(6) + "~" + today;
        }
        Matcher m = DATE_PATTERN.matcher(message);
        if (m.find()) {
            String first = m.group(1);
            if (m.find()) {
                return first + "~" + m.group(1);
            }
            return first + "~" + first; // 单日期 → 当日
        }
        if (message.contains("上周")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return monday + "~" + monday.plusDays(6);
        }
        if (message.contains("本周")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return monday + "~" + today;
        }
        if (message.contains("昨天")) {
            return today.minusDays(1) + "~" + today.minusDays(1);
        }
        if (message.contains("今天")) {
            return today + "~" + today;
        }
        return today.minusDays(6) + "~" + today;
    }
}
