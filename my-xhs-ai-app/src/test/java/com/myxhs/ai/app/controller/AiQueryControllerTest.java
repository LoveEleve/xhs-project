package com.myxhs.ai.app.controller;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AiQueryController.extractWindow 单元测试（纯逻辑，无 DB/模型）。
 */
class AiQueryControllerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void 两个日期任意分隔() {
        assertEquals("2026-08-01~2026-08-07", AiQueryController.extractWindow("2026-08-01 到 2026-08-07 的下单量"));
        assertEquals("2026-08-10~2026-08-13", AiQueryController.extractWindow("2026-08-10 至 2026-08-13"));
        assertEquals("2026-08-01~2026-08-07", AiQueryController.extractWindow("2026-08-01~2026-08-07"));
    }

    @Test
    void 单日期为当日() {
        assertEquals("2026-08-01~2026-08-01", AiQueryController.extractWindow("2026-08-01 订单量"));
    }

    @Test
    void 今天昨天本周上周() {
        LocalDate today = LocalDate.now(ZONE);
        assertEquals(today + "~" + today, AiQueryController.extractWindow("今天订单量"));
        assertEquals(today.minusDays(1) + "~" + today.minusDays(1), AiQueryController.extractWindow("昨天支付成功率"));
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertEquals(monday + "~" + today, AiQueryController.extractWindow("本周订单"));
        assertEquals(monday.minusWeeks(1) + "~" + monday.minusWeeks(1).plusDays(6),
                AiQueryController.extractWindow("上周订单情况"));
    }

    @Test
    void 无日期默认最近七天() {
        LocalDate today = LocalDate.now(ZONE);
        assertEquals(today.minusDays(6) + "~" + today, AiQueryController.extractWindow("帮我查一下订单量"));
        assertEquals(today.minusDays(6) + "~" + today, AiQueryController.extractWindow(null));
    }
}
