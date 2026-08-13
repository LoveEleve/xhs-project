package com.myxhs.ai.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrderMetricsTool 契约测试（H2 MySQL 模式，无网络/无 key）。
 * 验证口径（指标字典 v0.2 §3#8）：排除已删、含取消/退款、按窗口、Asia/Shanghai 半开区间 [from, to)。
 */
class OrderMetricsToolTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS t_order_0 (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT,
                    order_no VARCHAR(64),
                    status TINYINT,
                    created_at DATETIME,
                    deleted TINYINT DEFAULT 0
                )
                """);
        jdbc.update("DELETE FROM t_order_0");
        // 窗口：2026-08-01 ~ 2026-08-07（含 8-01，不含 8-08）
        // 8-01 正常订单（计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (1, 100, 'o1', 1, '2026-08-01 00:00:00', 0)");
        // 8-03 已取消 status=4（口径=含取消 → 计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (2, 100, 'o2', 4, '2026-08-03 12:00:00', 0)");
        // 8-05 已退款 status=3（口径=含退款 → 计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (3, 100, 'o3', 3, '2026-08-05 12:00:00', 0)");
        // 8-06 逻辑删除 deleted=1（口径=排除已删 → 不计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (4, 100, 'o4', 1, '2026-08-06 12:00:00', 1)");
        // 7-31（窗口外早于 8-01 → 不计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (5, 100, 'o5', 1, '2026-07-31 23:59:59', 0)");
        // 8-08 00:00:00（半开区间 [from,to)，to 不含 → 不计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (6, 100, 'o6', 1, '2026-08-08 00:00:00', 0)");
        // 8-08 00:00:01（超出 → 不计入）
        jdbc.update("INSERT INTO t_order_0 VALUES (7, 100, 'o7', 1, '2026-08-08 00:00:01', 0)");
    }

    @Test
    void 口径_排除已删且含取消退款且按窗口() {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "t_order_0");
        String result = tool.queryOrderVolume("2026-08-01~2026-08-07");
        System.out.println(result);
        // 期望 value=3：id 1,2,3 计入；deleted=1(id4) 排除；7-31(id5) 窗口外；8-08(id6/7) 半开区间外
        assertTrue(result.contains("\"value\":3"), "应为 3，实际: " + result);
        assertTrue(result.contains("\"definitionVersion\":\"order.order_volume/v1\""));
        assertTrue(result.contains("\"metric\":\"order.query_volume\""));
        assertTrue(result.contains("\"zone\":\"Asia/Shanghai\""));
    }

    @Test
    void 空窗口返回0() {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "t_order_0");
        String result = tool.queryOrderVolume("2026-09-01~2026-09-07");
        assertTrue(result.contains("\"value\":0"), "应为 0，实际: " + result);
    }

    @Test
    void 非法时间窗抛错() {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "t_order_0");
        assertThrows(IllegalArgumentException.class, () -> tool.queryOrderVolume("bad-window"));
        assertThrows(IllegalArgumentException.class, () -> tool.queryOrderVolume("2026-08-07~2026-08-01"));
    }

    @Test
    void 窗口超上限抛错() {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "t_order_0");
        // 32 天 > 31 天上限
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryOrderVolume("2026-08-01~2026-09-01"));
        // 正好 31 天不抛
        tool.queryOrderVolume("2026-08-01~2026-08-31");
    }

    @Test
    void 部分分片失败返回partial不抛错() {
        // t_order_0 正常 + 不存在的表 → 应返回 partial=true 且 value 来自正常分片（3）
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "t_order_0,no_such_table_xyz");
        String result = tool.queryOrderVolume("2026-08-01~2026-08-07");
        System.out.println(result);
        assertTrue(result.contains("\"status\":\"partial\""), "应为 partial，实际: " + result);
        assertTrue(result.contains("\"value\":3"), "value 应来自正常分片=3，实际: " + result);
        assertTrue(result.contains("\"partial\":true"));
    }

    @Test
    void 全部分片失败抛错() {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "no_such_a,no_such_b");
        assertThrows(IllegalStateException.class, () -> tool.queryOrderVolume("2026-08-01~2026-08-07"));
    }

    @Test
    void 窗口边界_单日() {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "t_order_0");
        // 单日 08-01：只有 id1
        String result = tool.queryOrderVolume("2026-08-01~2026-08-01");
        assertTrue(result.contains("\"value\":1"), "单日应为 1，实际: " + result);
        assertEquals(2, OrderMetricsTool.parseWindow("2026-08-01~2026-08-01").length);
    }
}
