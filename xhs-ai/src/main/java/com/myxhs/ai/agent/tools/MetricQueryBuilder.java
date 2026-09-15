package com.myxhs.ai.agent.tools;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 业务级指标查询 → PromQL 组装（白名单目录，不把 PromQL 暴露给模型）
 */
public final class MetricQueryBuilder {

    /** 允许的指标目录（instant TopN） */
    public enum Metric {
        ERROR_RATE("error_rate", "各服务 5xx 错误率（%）", "%"),
        QPS("qps", "各服务请求 QPS", "req/s"),
        LATENCY_P95("latency_p95", "各服务 P95 延迟（ms）", "ms"),
        SLOW_URI("slow_uri", "P95 最慢接口（ms，可按服务过滤）", "ms"),
        HEAP_MB("heap_mb", "各服务 JVM 堆使用（MB）", "MB");

        public final String key;
        public final String description;
        public final String unit;

        Metric(String key, String description, String unit) {
            this.key = key;
            this.description = description;
            this.unit = unit;
        }

        public static Metric from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("metric 必填");
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Metric m : values()) {
                if (m.key.equals(normalized)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("不支持的 metric: " + value + "（可选：" + keys() + "）");
        }

        public static String keys() {
            StringBuilder sb = new StringBuilder();
            for (Metric m : values()) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(m.key);
            }
            return sb.toString();
        }
    }

    private static final Pattern SERVICE = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");
    private static final String METRIC = "http_server_requests_seconds";

    private MetricQueryBuilder() {
    }

    public static void validateService(String service) {
        if (service != null && !service.isBlank() && !SERVICE.matcher(service).matches()) {
            throw new IllegalArgumentException("service 名称非法");
        }
    }

    /** instant 查询：window 固定 5m（与展示口径一致），返回 PromQL */
    public static String instant(Metric metric, String service) {
        validateService(service);
        String matcher = service == null || service.isBlank() ? "" : "{application=\"" + service + "\"}";
        switch (metric) {
            case ERROR_RATE:
                return "sum by (application) (rate(" + METRIC + "_count{status=~\"5..\"}[5m]))"
                        + " / clamp_min(sum by (application) (rate(" + METRIC + "_count[5m])), 0.000001) * 100";
            case QPS:
                return "sum by (application) (rate(" + METRIC + "_count[5m]))";
            case LATENCY_P95:
                return "histogram_quantile(0.95, sum by (le, application)"
                        + " (rate(" + METRIC + "_bucket[5m]))) * 1000";
            case SLOW_URI:
                String grouping = service == null || service.isBlank()
                        ? "sum by (le, application, uri)" : "sum by (le, uri)";
                return "histogram_quantile(0.95, " + grouping
                        + " (rate(" + METRIC + "_bucket" + matcher + "[5m]))) * 1000";
            case HEAP_MB:
                return "sum by (application) (jvm_memory_used_bytes{area=\"heap\"}) / 1048576";
            default:
                throw new IllegalArgumentException("不支持的 metric");
        }
    }

    /** range 查询：趋势（service 必填），返回 PromQL */
    public static String trend(Metric metric, String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("metric_trend 必须指定 service");
        }
        validateService(service);
        String base;
        switch (metric) {
            case ERROR_RATE:
                base = "sum (rate(" + METRIC + "_count{application=\"" + service + "\",status=~\"5..\"}[5m]))"
                        + " / clamp_min(sum (rate(" + METRIC + "_count{application=\"" + service + "\"}[5m])), 0.000001) * 100";
                break;
            case QPS:
                base = "sum (rate(" + METRIC + "_count{application=\"" + service + "\"}[5m]))";
                break;
            case LATENCY_P95:
                base = "histogram_quantile(0.95, sum by (le) (rate(" + METRIC + "_bucket{application=\""
                        + service + "\"}[5m]))) * 1000";
                break;
            default:
                throw new IllegalArgumentException("趋势仅支持 error_rate/qps/latency_p95");
        }
        return base;
    }

    private static final java.util.regex.Pattern PROMQL_METRIC = java.util.regex.Pattern.compile(
            "\\b(http_server_requests_seconds|http_client_requests_seconds|jvm_[a-z_]+|hikaricp_[a-z_]+|"
            + "system_[a-z_]+|process_[a-z_]+|prometheus_[a-z_]+|rocketmq_[a-z_]+|ai_[a-z_]+|up|node_[a-z_]+)");

    /** 裸 PromQL 兜底（自研 metric_query）校验：长度 + 已知指标白名单，拒绝空查询 */
    public static void validatePromql(String promql) {
        if (promql == null || promql.isBlank()) {
            throw new IllegalArgumentException("promql 必填");
        }
        if (promql.length() > 600) {
            throw new IllegalArgumentException("promql 过长（>600 字符）");
        }
        if (!PROMQL_METRIC.matcher(promql).find()) {
            throw new IllegalArgumentException("promql 未包含已知指标名，请使用业务级工具或检查指标名");
        }
    }

    public static int clampMinutes(int minutes) {
        return Math.max(5, Math.min(1440, minutes));
    }

    public static int trendStepSeconds(int minutes) {
        int step = Math.max(15, Math.min(300, minutes * 60 / 60));
        return step;
    }
}
