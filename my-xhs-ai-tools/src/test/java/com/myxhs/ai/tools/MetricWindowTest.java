package com.myxhs.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MetricWindow 值类测试：解析/校验/跨度/基线（单一事实源）。
 */
class MetricWindowTest {

    @Test
    void 解析合法窗口() {
        MetricWindow w = MetricWindow.parse("2026-08-01~2026-08-07");
        assertEquals(7, w.spanDays());
        assertEquals("2026-08-01~2026-08-07", w.format());
    }

    @Test
    void 基线_上一同长窗口() {
        assertEquals("2026-07-25~2026-07-31", MetricWindow.parse("2026-08-01~2026-08-07").baseline().format());
        assertEquals("2026-08-06~2026-08-06", MetricWindow.parse("2026-08-07~2026-08-07").baseline().format());
        assertEquals("2026-07-01~2026-07-31", MetricWindow.parse("2026-08-01~2026-08-31").baseline().format());
    }

    @Test
    void 跨年窗口_基线正确回退() {
        assertEquals("2025-12-25~2025-12-31", MetricWindow.parse("2026-01-01~2026-01-07").baseline().format());
    }

    @Test
    void 闰年二月窗口() {
        assertEquals("2024-02-01~2024-02-29", MetricWindow.parse("2024-02-01~2024-02-29").format());
        assertEquals("2024-01-03~2024-01-31", MetricWindow.parse("2024-02-01~2024-02-29").baseline().format());
    }

    @Test
    void 非法窗口_抛异常带原因() {
        assertThrows(IllegalArgumentException.class, () -> MetricWindow.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MetricWindow.parse(null));
        assertThrows(IllegalArgumentException.class, () -> MetricWindow.parse("20260801~20260807"));
        assertThrows(IllegalArgumentException.class, () -> MetricWindow.parse("2026-08-07~2026-08-01"));
        assertThrows(IllegalArgumentException.class, () -> MetricWindow.parse("2026-08-01~2026-09-05"));
    }

    @Test
    void 上限31天边界_恰好允许() {
        MetricWindow w = MetricWindow.parse("2026-08-01~2026-08-31");
        assertEquals(31, w.spanDays());
    }
}
