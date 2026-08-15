package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 真实指标工具：payment.success_rate（支付成功率）。
 * 口径（指标字典 v0.2 §3#7）：成功(status=1) / (成功 + 失败(status=2))，**分母排除 0待支付/3退款**；
 * 时间基准=created_at（支付尝试发生窗），Asia/Shanghai，窗口≤31天。
 * 表：my_xhs_payment.t_payment（单表，不分片）；渠道为 Mock（pay_type 1支付宝/2微信），结论须注明。
 */
public class PaymentMetricsTool {

    private static final Logger log = LoggerFactory.getLogger(PaymentMetricsTool.class);

    public static final String METRIC = "payment.success_rate";
    public static final String DEF_VERSION = "payment.success_rate/v1";
    public static final String SOURCE = "my_xhs_payment.t_payment";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int MAX_WINDOW_DAYS = 31;
    private static final String TABLE = "my_xhs_payment.t_payment";

    private final JdbcTemplate jdbc;

    public PaymentMetricsTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool("查询指定时间窗内的支付成功率（口径：成功/(成功+失败)，排除待支付/退款；渠道为 Mock；窗口≤31天）")
    public String paymentSuccessRate(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07，跨度不超过31天") String window) {
        LocalDateTime[] bounds = MetricTimeWindow.parse(window, MAX_WINDOW_DAYS);
        try {
            return doQuery(bounds, window);
        } catch (Exception e) {
            log.error("[metric] {} 查询失败: window={}, err={}", METRIC, window, e.getMessage());
            return ToolJson.error(METRIC, e.getMessage());
        }
    }

    private String doQuery(LocalDateTime[] bounds, String window) {
        // 总计：成功/失败（排除 0/3）
        Map<String, Object> total = jdbc.queryForMap(
                "SELECT " +
                        "  COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS success, " +
                        "  COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS fail " +
                        "FROM " + TABLE +
                        " WHERE created_at >= ? AND created_at < ? AND deleted = 0",
                bounds[0], bounds[1]);

        long success = ((Number) total.get("success")).longValue();
        long fail = ((Number) total.get("fail")).longValue();
        double rate = (success + fail) == 0 ? 0.0 : (double) success / (success + fail);

        // 分渠道
        List<ChannelRate> channels = new java.util.ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList(
                "SELECT pay_type AS payType, " +
                        "  COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS success, " +
                        "  COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS fail " +
                        "FROM " + TABLE +
                        " WHERE created_at >= ? AND created_at < ? AND deleted = 0 " +
                        "GROUP BY pay_type",
                bounds[0], bounds[1])) {
            long cs = ((Number) c.get("success")).longValue();
            long cf = ((Number) c.get("fail")).longValue();
            double cr = (cs + cf) == 0 ? 0.0 : (double) cs / (cs + cf);
            channels.add(new ChannelRate(String.valueOf(c.get("payType")),
                    Double.parseDouble(String.format("%.4f", cr)), cs, cf));
        }

        log.info("[metric] {} window={} rate={} success={} fail={} channels={}",
                METRIC, window, String.format("%.4f", rate), success, fail, channels.size());

        return ToolJson.write(new PaymentRateResult(
                "ok", METRIC, DEF_VERSION, window, "Asia/Shanghai",
                Double.parseDouble(String.format("%.4f", rate)), success, fail, channels,
                LocalDateTime.now(ZONE).toString(), SOURCE,
                "渠道 pay_type: 1=支付宝 2=微信 99=Mock(模拟支付)；rate 按查询时刻已决支付计(待支付0后续可能改变结果)"));
    }

    /** 支付成功率结果（类型化契约） */
    public record PaymentRateResult(
            String status, String metric, String definitionVersion, String window, String zone,
            double value, long success, long fail, List<ChannelRate> channels,
            String asOf, String source, String note) {
    }

    /** 分渠道成功率 */
    public record ChannelRate(String channel, double rate, long success, long fail) {
    }
}
