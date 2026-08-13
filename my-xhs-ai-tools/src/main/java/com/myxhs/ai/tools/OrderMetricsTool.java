package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 真实指标工具：order.query_volume（下单量）。
 * 口径（指标字典 v0.2 §3#8）：排除已删(deleted=0)、含取消/退款（不按 status 过滤），
 * 按 created_at 时间窗 + Asia/Shanghai。
 * 分片：ShardingSphere 拓扑 ds0..3 × t_order_0..3 = 16 实际节点（my-xhs-order/sharding-config.yaml）。
 * 可靠性：窗口上限（≤31天）、逐分片容错（单节点失败返回 partial + warnings）。
 * 只读账号：myxhs_ai_ro（GRANT SELECT），密码走 env（D2 Gate）。
 */
public class OrderMetricsTool {

    private static final Logger log = LoggerFactory.getLogger(OrderMetricsTool.class);

    public static final String METRIC = "order.query_volume";
    public static final String DEF_VERSION = "order.order_volume/v1";
    public static final String SOURCE = "order_shard_scan(16 nodes)";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int MAX_WINDOW_DAYS = 31;

    private final JdbcTemplate jdbc;
    private final List<String> shards;

    public OrderMetricsTool(JdbcTemplate jdbc, String shardsCsv) {
        this.jdbc = jdbc;
        if (StringUtils.hasText(shardsCsv)) {
            this.shards = Arrays.stream(shardsCsv.split(","))
                    .map(String::trim).filter(StringUtils::hasText).toList();
        } else {
            // 默认按 ShardingSphere 拓扑 ds0..3 × t_order_0..3 = 16 节点
            this.shards = new ArrayList<>();
            for (int db = 0; db < 4; db++) {
                for (int t = 0; t < 4; t++) {
                    this.shards.add("my_xhs_order_" + db + ".t_order_" + t);
                }
            }
        }
    }

    @Tool("查询指定时间窗内的下单量（口径：排除已删、含取消/退款，按订单创建时间，Asia/Shanghai，窗口≤31天）")
    public String queryOrderVolume(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07，跨度不超过31天") String window) {
        LocalDateTime[] bounds = parseWindow(window);

        long volume = 0;
        List<String> warnings = new ArrayList<>();
        int failed = 0;
        for (String shard : shards) {
            try {
                Long n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + shard
                                + " WHERE created_at >= ? AND created_at < ? AND deleted = 0",
                        Long.class, bounds[0], bounds[1]);
                volume += (n == null ? 0 : n);
            } catch (Exception e) {
                failed++;
                warnings.add(shard + ": " + e.getMessage());
                log.warn("[metric] 分片查询失败(跳过): shard={}, err={}", shard, e.getMessage());
            }
        }
        if (failed == shards.size()) {
            throw new IllegalStateException("order.query_volume 全部分片查询失败: " + warnings);
        }

        boolean partial = failed > 0;
        log.info("[metric] {} window={} volume={} partial={} failedShards={}/{}",
                METRIC, window, volume, partial, failed, shards.size());

        // warnings 恒为 JSON 数组（空=[]，有值=[...]），保证契约类型一致
        StringBuilder warningsJson = new StringBuilder("[");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                warningsJson.append(",");
            }
            warningsJson.append("\"").append(warnings.get(i).replace("\"", "\\\"")).append("\"");
        }
        warningsJson.append("]");

        return "{\"status\":\"" + (partial ? "partial" : "ok") + "\",\"metric\":\"" + METRIC
                + "\",\"definitionVersion\":\"" + DEF_VERSION
                + "\",\"window\":\"" + window + "\",\"zone\":\"Asia/Shanghai\",\"value\":" + volume
                + ",\"unit\":\"单\",\"asOf\":\"" + LocalDateTime.now(ZONE) + "\",\"source\":\"" + SOURCE
                + "\",\"partial\":" + partial
                + ",\"warnings\":" + warningsJson + "}";
    }

    /** 解析 "yyyy-MM-dd~yyyy-MM-dd" → [from 00:00, to+1day 00:00)；超 MAX_WINDOW_DAYS 拒绝 */
    static LocalDateTime[] parseWindow(String window) {
        return MetricTimeWindow.parse(window, MAX_WINDOW_DAYS);
    }
}
