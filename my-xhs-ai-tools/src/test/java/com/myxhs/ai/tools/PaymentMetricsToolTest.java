package com.myxhs.ai.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PaymentMetricsTool 契约测试（H2 MySQL 模式，无网络）。
 * 口径（指标字典 v0.2 §3#7）：成功(status=1)/(成功+失败(status=2))，排除 0待支付/3退款/已删，按窗口。
 */
class PaymentMetricsToolTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:paytest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS my_xhs_payment");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS my_xhs_payment.t_payment (
                    id BIGINT PRIMARY KEY,
                    order_id BIGINT,
                    user_id BIGINT,
                    payment_no VARCHAR(64),
                    amount DECIMAL(10,2),
                    pay_type TINYINT,
                    status TINYINT,
                    paid_at DATETIME,
                    deleted TINYINT DEFAULT 0,
                    created_at DATETIME
                )
                """);
        jdbc.update("DELETE FROM my_xhs_payment.t_payment");
        // 窗口 08-01~08-07
        // 成功 3（2 个支付宝 type=1，1 个微信 type=2）
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (1,1,100,'p1',10.00,1,1,'2026-08-02 10:00:00',0,'2026-08-01 09:00:00')");
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (2,2,100,'p2',20.00,1,1,'2026-08-03 10:00:00',0,'2026-08-02 09:00:00')");
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (3,3,100,'p3',30.00,2,1,'2026-08-04 10:00:00',0,'2026-08-03 09:00:00')");
        // 失败 1（微信 type=2）
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (4,4,100,'p4',40.00,2,2,NULL,0,'2026-08-05 09:00:00')");
        // 待支付 status=0（排除）
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (5,5,100,'p5',50.00,1,0,NULL,0,'2026-08-06 09:00:00')");
        // 已退款 status=3（排除）
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (6,6,100,'p6',60.00,1,3,'2026-08-06 10:00:00',0,'2026-08-01 08:00:00')");
        // 已删除（排除）
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (7,7,100,'p7',70.00,1,1,'2026-08-06 11:00:00',1,'2026-08-06 08:00:00')");
        // 窗口外（排除）
        jdbc.update("INSERT INTO my_xhs_payment.t_payment VALUES (8,8,100,'p8',80.00,1,1,'2026-07-30 10:00:00',0,'2026-07-30 09:00:00')");
    }

    @Test
    void 口径_成功除成功加失败_排除待支付退款已删() {
        PaymentMetricsTool tool = new PaymentMetricsTool(jdbc);
        String result = tool.paymentSuccessRate("2026-08-01~2026-08-07");
        System.out.println(result);
        // 期望：success=3, fail=1, rate=3/4=0.75；排除 2 待支付 + 1 退款 + 1 已删 + 1 窗外
        assertTrue(result.contains("\"success\":3"), "success 应为 3，实际: " + result);
        assertTrue(result.contains("\"fail\":1"), "fail 应为 1，实际: " + result);
        assertTrue(result.contains("\"value\":0.7500"), "rate 应为 0.7500，实际: " + result);
        assertTrue(result.contains("\"channels\":["));
        // 支付宝 type=1：success=2, fail=0 → rate 1.0；微信 type=2：success=1, fail=1 → 0.5
        assertTrue(result.contains("\"rate\":1.0000"), "支付宝渠道应 1.0: " + result);
        assertTrue(result.contains("\"rate\":0.5000"), "微信渠道应 0.5: " + result);
    }

    @Test
    void 空窗口_rate为0() {
        PaymentMetricsTool tool = new PaymentMetricsTool(jdbc);
        String result = tool.paymentSuccessRate("2026-09-01~2026-09-07");
        assertTrue(result.contains("\"value\":0.0000"), "空窗口应为 0，实际: " + result);
        assertTrue(result.contains("\"success\":0"));
    }

    @Test
    void 非法窗或超上限抛错() {
        PaymentMetricsTool tool = new PaymentMetricsTool(jdbc);
        assertThrows(IllegalArgumentException.class, () -> tool.paymentSuccessRate("bad"));
        assertThrows(IllegalArgumentException.class,
                () -> tool.paymentSuccessRate("2026-08-01~2026-09-01"));
    }
}
