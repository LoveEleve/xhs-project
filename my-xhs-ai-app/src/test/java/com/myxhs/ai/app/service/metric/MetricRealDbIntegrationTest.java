package com.myxhs.ai.app.service.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实库集成断言（仅当 MYXHS_DB_PASSWORD 已注入时运行，CI 无凭据自动跳过）。
 * 用只读账号连真实 MySQL，直接断言工具原始 JSON（非 Agent 转述）：
 * - order.query_volume 窗口 2026-08-01~08-07 = 61（16 节点）
 * - payment.success_rate 窗口 2026-08-10~08-13 = 0.5（success4/fail4）
 * - content.interaction 窗口 2026-08-10~08-13 = 48（seed 自愈，表空自动重跑 db/seed-a3.sql）
 * ⚠️ 依赖当前真实数据快照 + A3 seed；数据变化时按实际 SQL 复核更新期望值。
 */
@EnabledIfEnvironmentVariable(named = "MYXHS_DB_PASSWORD", matches = ".+")
class MetricRealDbIntegrationTest {

    private JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        String url = System.getenv().getOrDefault("MYXHS_DB_URL",
                "jdbc:mysql://21.130.247.89:3306/my_xhs_order_0?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai");
        String user = System.getenv().getOrDefault("MYXHS_DB_USER", "myxhs_ai_ro");
        String password = System.getenv("MYXHS_DB_PASSWORD");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void 真实库_order_query_volume_61() throws Exception {
        OrderMetricsTool tool = new OrderMetricsTool(jdbc, "");
        String raw = tool.queryOrderVolume("2026-08-01~2026-08-07");
        System.out.println("[real-order] " + raw);
        JsonNode node = om.readTree(raw);
        assertTrue(node.path("status").asText().equals("ok") || node.path("status").asText().equals("partial"),
                "status 应为 ok/partial: " + raw);
        assertTrue(node.path("value").asLong() == 61L,
                "value 应为 61（16 节点全量），实际: " + raw);
    }

    @Test
    void 真实库_payment_success_rate_05() throws Exception {
        PaymentMetricsTool tool = new PaymentMetricsTool(jdbc);
        String raw = tool.paymentSuccessRate("2026-08-10~2026-08-13");
        System.out.println("[real-payment] " + raw);
        JsonNode node = om.readTree(raw);
        assertTrue(node.path("status").asText().equals("ok"), "status 应为 ok: " + raw);
        assertTrue(node.path("success").asLong() == 4L, "success 应为 4: " + raw);
        assertTrue(node.path("fail").asLong() == 4L, "fail 应为 4: " + raw);
        assertTrue(Math.abs(node.path("value").asDouble() - 0.5) < 1e-9, "value 应为 0.5: " + raw);
    }

    @Test
    void 真实库_content_interaction_48() throws Exception {
        // 依赖 A3 seed（可重跑，db/seed-a3.sql）。dev 库可能被重置 → 表空时用 root（MYXHS_ROOT_PASSWORD）自动重跑 seed 自愈。
        ensureContentSeed();
        ContentInteractionTool tool = new ContentInteractionTool(jdbc);
        String raw = tool.contentInteraction("2026-08-10~2026-08-13");
        System.out.println("[real-content] " + raw);
        JsonNode node = om.readTree(raw);
        assertTrue(node.path("status").asText().equals("ok"), "status 应为 ok: " + raw);
        assertTrue(node.path("total").asLong() == 48L, "total 应为 48（seed 骤降）: " + raw);
        assertTrue(node.path("exposure").asLong() == 16L, "exposure 应为 16: " + raw);
        assertTrue(node.path("daily").size() == 4, "daily 应 4 天: " + raw);
    }

    /** 表空时用 root 账号执行 db/seed-a3.sql（自愈），否则跳过 */
    private void ensureContentSeed() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM my_xhs_content.t_user_behavior", Long.class);
        if (count != null && count > 0) {
            return;
        }
        String rootPw = System.getenv("MYXHS_ROOT_PASSWORD");
        if (rootPw == null || rootPw.isBlank()) {
            throw new IllegalStateException("t_user_behavior 为空且未提供 MYXHS_ROOT_PASSWORD（无法自愈 seed）；请先执行 src/main/resources/db/seed-a3.sql");
        }
        DriverManagerDataSource root = new DriverManagerDataSource();
        root.setDriverClassName("com.mysql.cj.jdbc.Driver");
        root.setUrl("jdbc:mysql://21.130.247.89:3306/my_xhs_content?useSSL=false&serverTimezone=Asia/Shanghai");
        root.setUsername("root");
        root.setPassword(rootPw);
        JdbcTemplate rootJdbc = new JdbcTemplate(root);
        String sql;
        try (var in = getClass().getResourceAsStream("/db/seed-a3.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 seed-a3.sql 失败", e);
        }
        for (String stmt : sql.split(";")) {
            String s = stmt.trim();
            if (s.isEmpty() || s.startsWith("--") || s.startsWith("USE") || s.startsWith("/*")) {
                continue;
            }
            rootJdbc.execute(s);
        }
    }
}
