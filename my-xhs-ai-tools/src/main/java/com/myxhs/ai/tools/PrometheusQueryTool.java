package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 观测查询工具（D4 B3 面：5xx/慢端点，L2 只读）。
 * 纯 JDK HttpClient 查 PromQL HTTP API，无 DB；结果 JSON 聚合（按 service/uri/status）。
 * 指标源：Spring Boot Actuator http_server_requests（my-xhs 服务已接入）。
 * 窗口语义：最近 N 小时（观测数据是近期时间序列，与业务日期窗不同）。
 */
public class PrometheusQueryTool {

    public static final String METRIC_HTTP_ERRORS = "service.http_errors";
    public static final String METRIC_HTTP_LATENCY = "service.http_latency";
    public static final String METRIC_MQ_LAG = "mq.consumer_lag";
    public static final String METRIC_MQ_DLQ = "mq.dlq_backlog";
    public static final String METRIC_MYSQL_REPLICA_LAG = "mysql.replication_lag";
    public static final String METRIC_MYSQL_DEADLOCKS = "mysql.deadlocks";

    /** MQ 组名/标签过滤白名单（防 PromQL 注入：组名只允许字母数字下划线连字符） */
    private static final java.util.regex.Pattern GROUP_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

    private final HttpClient http;
    private final ObjectMapper om;
    private final String prometheusUrl;

    public PrometheusQueryTool(String prometheusUrl) {
        this(prometheusUrl, new ObjectMapper());
    }

    public PrometheusQueryTool(String prometheusUrl, ObjectMapper om) {
        this.prometheusUrl = prometheusUrl;
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 5xx 错误统计（按 uri+status 聚合，最近 N 小时）；service 为空则查全部 */
    public String httpErrors(String service, String hours) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_HTTP_ERRORS);
        node.put("asOf", java.time.Instant.now().toString());
        int h;
        try {
            h = Integer.parseInt(hours);
            if (h <= 0 || h > 168) {
                return error("hours 必须在 1~168 之间: " + hours);
            }
        } catch (NumberFormatException e) {
            return error("hours 必须为整数（最近 N 小时）: " + hours);
        }
        node.put("hours", h);
        String range = h + "h";
        String svcFilter = (service == null || service.isBlank()) ? "" : "service=\"" + service + "\",";
        Map<String, Double> byUri = new LinkedHashMap<>();
        try {
            // 最近 N 小时 5xx 错误总数（按 uri+status+service 聚合）
            JsonNode resp = query("sum by (uri,status,service) (increase(http_server_requests_seconds_count{"
                    + svcFilter + "status=~\"5..\"}[" + range + "]))");
            double total5xx = 0;
            double noiseScan = 0;
            for (JsonNode s : resp) {
                double v = s.path("value").get(1).asDouble();
                total5xx += v;
                String uri = s.path("metric").path("uri").asText();
                if ("/**".equals(uri)) {
                    // 扫描/探测噪音（Gateway NoResourceFoundException 兜底）：单列，不并入业务归因
                    noiseScan += v;
                } else {
                    byUri.merge(uri, v, Double::sum);
                }
            }
            node.put("total5xx", round(total5xx));
            node.put("noiseScanRoutes", round(noiseScan));
            node.put("window", "最近 " + h + " 小时（错误总数）");
            ArrayNode uris = node.putArray("byUri");
            byUri.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> uris.addObject().put("uri", e.getKey()).put("rate5xxPerSec", round(e.getValue())));
        } catch (Exception e) {
            return error("Prometheus 查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** 慢端点 top N（P95 延迟，最近 N 小时） */
    public String httpLatency(String service, String hours) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_HTTP_LATENCY);
        node.put("asOf", java.time.Instant.now().toString());
        int h;
        try {
            h = Integer.parseInt(hours);
            if (h <= 0 || h > 168) {
                return error("hours 必须在 1~168 之间: " + hours);
            }
        } catch (NumberFormatException e) {
            return error("hours 必须为整数（最近 N 小时）: " + hours);
        }
        node.put("hours", h);
        String range = h + "h";
        String svcFilter = (service == null || service.isBlank()) ? "" : "service=\"" + service + "\",";
        try {
            String q = "histogram_quantile(0.95, sum by (le,uri,service) (rate(http_server_requests_seconds_bucket{"
                    + svcFilter + "}[" + range + "])))";
            JsonNode resp = query(q);
            List<JsonNode> sorted = new ArrayList<>();
            for (JsonNode s : resp) {
                sorted.add(s);
            }
            sorted.sort((a, b) -> Double.compare(
                    b.path("value").get(1).asDouble(), a.path("value").get(1).asDouble()));
            node.put("window", "最近 " + h + " 小时（P95 延迟）");
            ArrayNode uris = node.putArray("topLatency");
            for (JsonNode s : sorted.stream().limit(10).toList()) {
                double v = s.path("value").get(1).asDouble();
                if (v <= 0) {
                    continue;
                }
                uris.addObject()
                        .put("service", s.path("metric").path("service").asText())
                        .put("uri", s.path("metric").path("uri").asText())
                        .put("p95Seconds", round(v));
            }
        } catch (Exception e) {
            return error("Prometheus 查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** RocketMQ 消费积压（按 group 聚合；group 空=全部，top 15） */
    public String mqConsumerLag(String group) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_MQ_LAG);
        node.put("asOf", java.time.Instant.now().toString());
        String filter = safeGroupFilter(group, "group");
        if (filter == null) {
            return error("group 含非法字符（仅允许字母数字下划线连字符）: " + group);
        }
        try {
            JsonNode resp = query("sum by (group) (rocketmq_consumer_lag{" + filter + "})");
            double total = 0;
            int sentinelCount = 0;
            java.util.List<Map.Entry<String, Double>> lags = new ArrayList<>();
            for (JsonNode s : resp) {
                double v = s.path("value").get(1).asDouble();
                // 哨兵口径（2026-08-16 中间件核实）：-1 = mqadmin 哨兵（无在线消费者/无已提交 offset），
                // 不参与聚合（避免负总积压误导）；0 = 真实无积压，保留
                if (v < 0) {
                    sentinelCount++;
                    continue;
                }
                total += v;
                lags.add(Map.entry(s.path("metric").path("group").asText(), v));
            }
            node.put("totalLag", round(total));
            node.put("sentinelGroups", sentinelCount);
            node.put("groupCount", lags.size());
            node.put("note", "totalLag 排除 -1 哨兵组（无在线消费者/无已提交 offset）；0 = 真实无积压");
            node.put("window", "当前时点（textfile 管道每 5 分钟采集）");
            ArrayNode groups = node.putArray("topGroups");
            lags.stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> groups.addObject().put("group", e.getKey()).put("lag", round(e.getValue())));
        } catch (Exception e) {
            return error("Prometheus 查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** RocketMQ 死信积压（按 consumer_group 聚合；空=全部，top 15） */
    public String mqDlqBacklog(String consumerGroup) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_MQ_DLQ);
        node.put("asOf", java.time.Instant.now().toString());
        String filter = safeGroupFilter(consumerGroup, "consumer_group");
        if (filter == null) {
            return error("consumerGroup 含非法字符（仅允许字母数字下划线连字符）: " + consumerGroup);
        }
        try {
            JsonNode resp = query("sum by (consumer_group) (rocketmq_dlq_backlog{" + filter + "})");
            double total = 0;
            int sentinelCount = 0;
            java.util.List<Map.Entry<String, Double>> lags = new ArrayList<>();
            for (JsonNode s : resp) {
                double v = s.path("value").get(1).asDouble();
                // 哨兵口径（2026-08-16 中间件核实）：-1 = 无 DLQ 或查询失败（应用侧哨兵），
                // 求和无意义（-283 是 -1 行的累加）——正确口径只计 >0 的组
                if (v <= 0) {
                    sentinelCount++;
                    continue;
                }
                total += v;
                lags.add(Map.entry(s.path("metric").path("consumer_group").asText(), v));
            }
            node.put("totalDlqBacklog", round(total));
            node.put("sentinelGroups", sentinelCount);
            node.put("groupCount", lags.size());
            node.put("note", "totalDlqBacklog 只计 backlog>0 的消费组；-1 为无 DLQ/查询失败哨兵，不参与聚合");
            node.put("window", "当前时点（客户端指标）");
            ArrayNode groups = node.putArray("topGroups");
            lags.stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> groups.addObject().put("group", e.getKey()).put("backlog", round(e.getValue())));
        } catch (Exception e) {
            return error("Prometheus 查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** 组名标签过滤器；非法返回 null（防 PromQL 注入） */
    private static String safeGroupFilter(String group, String label) {
        if (group == null || group.isBlank()) {
            return "";
        }
        if (!GROUP_PATTERN.matcher(group.trim()).matches()) {
            return null;
        }
        return label + "=\"" + group.trim() + "\"";
    }

    /** MySQL 主从复制延迟（Seconds_Behind_Master，全部从库；无参） */
    public String mysqlReplicationLag() {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_MYSQL_REPLICA_LAG);
        node.put("asOf", java.time.Instant.now().toString());
        try {
            JsonNode resp = query("mysql_slave_status_seconds_behind_master");
            node.put("replicaCount", resp.size());
            ArrayNode replicas = node.putArray("replicas");
            for (JsonNode s : resp) {
                replicas.addObject()
                        .put("instance", s.path("metric").path("instance").asText())
                        .put("masterHost", s.path("metric").path("master_host").asText())
                        .put("secondsBehindMaster", round(s.path("value").get(1).asDouble()));
            }
            node.put("window", "当前时点（mysqld-exporter，主库 9104/从库 9105）");
        } catch (Exception e) {
            return error("Prometheus 查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** MySQL 死锁事件（累计 total + 最新 new_events；无参） */
    public String mysqlDeadlocks() {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_MYSQL_DEADLOCKS);
        node.put("asOf", java.time.Instant.now().toString());
        try {
            JsonNode total = query("mysql_innodb_deadlock_total");
            JsonNode events = query("mysql_innodb_deadlock_new_events");
            double totalV = 0;
            for (JsonNode s : total) {
                totalV += s.path("value").get(1).asDouble();
            }
            double eventsV = 0;
            for (JsonNode s : events) {
                eventsV += s.path("value").get(1).asDouble();
            }
            node.put("deadlockTotal", round(totalV));
            node.put("deadlockNewEvents", round(eventsV));
            node.put("window", "当前时点（innodb-print-all-deadlocks 管道）");
        } catch (Exception e) {
            return error("Prometheus 查询失败: " + e.getMessage());
        }
        return write(node);
    }

    /** 执行 PromQL 查询，返回 result 数组 */
    private JsonNode query(String promql) throws Exception {
        String url = prometheusUrl + "/api/v1/query?query=" + java.net.URLEncoder.encode(promql, "UTF-8");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode body = om.readTree(resp.body());
        if (!"success".equals(body.path("status").asText())) {
            throw new IllegalStateException("PromQL 返回非 success: " + body);
        }
        return body.path("data").path("result");
    }

    private String error(String msg) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "error");
        node.put("metric", METRIC_HTTP_ERRORS);
        node.put("error", msg);
        return write(node);
    }

    private String write(ObjectNode node) {
        try {
            return om.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\"序列化失败\"}";
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
