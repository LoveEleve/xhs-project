package com.myxhs.ai.app.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QueryWindowExtractor 分支测试（显式 today，消除时间不可测性）。
 * 固定 2026-08-14（周五）：上周=08-03~08-09，本周=08-10~08-14，昨天=08-13，今天=08-14。
 */
class QueryWindowExtractorTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 14);

    @Test
    void 显式日期窗优先() {
        assertEquals("2026-08-01~2026-08-07", QueryWindowExtractor.extract("2026-08-01 到 2026-08-07 的下单量", FRIDAY));
        assertEquals("2026-08-01~2026-08-01", QueryWindowExtractor.extract("2026-08-01 订单量", FRIDAY));
    }

    @Test
    void 上周_返回完整上一周() {
        assertEquals("2026-08-03~2026-08-09", QueryWindowExtractor.extract("上周订单量怎么样", FRIDAY));
    }

    @Test
    void 本周_周一到今天() {
        assertEquals("2026-08-10~2026-08-14", QueryWindowExtractor.extract("本周互动量", FRIDAY));
    }

    @Test
    void 昨天与今天() {
        assertEquals("2026-08-13~2026-08-13", QueryWindowExtractor.extract("昨天的支付成功率", FRIDAY));
        assertEquals("2026-08-14~2026-08-14", QueryWindowExtractor.extract("今天的订单量", FRIDAY));
    }

    @Test
    void 周一边界_本周等于当天() {
        LocalDate monday = FRIDAY.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertEquals(monday + "~" + monday, QueryWindowExtractor.extract("本周订单量", monday));
        // 周一问上周 = 上一周完整周
        assertEquals(monday.minusWeeks(1) + "~" + monday.minusDays(1),
                QueryWindowExtractor.extract("上周订单量", monday));
    }

    @Test
    void 周日边界_本周从本周一算起() {
        LocalDate sunday = FRIDAY.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate monday = sunday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertEquals(monday + "~" + sunday, QueryWindowExtractor.extract("本周订单量", sunday));
    }

    @Test
    void 无时间语义_默认最近7天() {
        assertEquals("2026-08-08~2026-08-14", QueryWindowExtractor.extract("为什么订单量下降了", FRIDAY));
        assertEquals("2026-08-08~2026-08-14", QueryWindowExtractor.extract(null, FRIDAY));
        assertNotNull(QueryWindowExtractor.extract("为什么订单量下降了"));
        assertTrue(QueryWindowExtractor.extract("为什么订单量下降了").matches("\\d{4}-\\d{2}-\\d{2}~\\d{4}-\\d{2}-\\d{2}"));
    }
}
