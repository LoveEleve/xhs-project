package com.myxhs.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * HTTP 接口级指标采集 Filter
 * <p>
 * 为每个 HTTP 请求自动记录：
 * - myxhs_http_request_duration_seconds — 请求耗时（含 P50/P90/P95/P99 百分位数）
 * </p>
 * <p>
 * 与 Spring Boot 默认的 http_server_requests 指标的区别：
 * - 默认指标的 uri 标签会包含路径变量（如 /api/note/123），导致指标基数爆炸
 * - 本 Filter 对 URI 做归一化处理（/api/note/{id}），控制指标基数
 * - 排除健康检查等内部端点，减少噪音
 * </p>
 * <p>
 * 注意：此 Filter 仅在 Servlet 环境下生效。
 * Gateway 是 WebFlux 架构，不会加载此 Filter（需要单独的 WebFilter 实现）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 在 TraceId Filter 之后
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET) // 仅 Servlet 环境生效
public class ApiMetricsFilter extends OncePerRequestFilter {

    private final MeterRegistry registry;

    /** 排除的路径前缀（不记录指标） */
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/actuator", "/health", "/favicon.ico", "/error"
    );

    /**
     * 纯数字路径段正则：/12345 → /{id}
     * <p>
     * 预编译正则，避免每次请求都编译（Pattern.compile 有开销）。
     * </p>
     */
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+");

    /**
     * UUID / 雪花 ID / 订单号等路径段正则：/ORD202605150001 → /{id}
     * <p>
     * 匹配规则：路径段中同时包含字母和数字，且总长度 >= 10。
     * 为什么 >= 10 而不是 >= 8？
     * - "actuator" 是 8 个字符，"inventory" 是 9 个字符，这些是合法路径
     * - 雪花 ID（18~19 位数字）、UUID（32 位 hex）、订单号（ORD + 16 位）都 >= 10
     * - 阈值设为 10 可以避免误伤正常路径段
     * </p>
     */
    private static final Pattern MIXED_ID_SEGMENT = Pattern.compile("/(?=[a-zA-Z0-9]*[a-zA-Z])(?=[a-zA-Z0-9]*\\d)[a-zA-Z0-9]{10,}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // 排除内部端点
        if (shouldExclude(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        long startTime = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationNanos = System.nanoTime() - startTime;
            String normalizedUri = normalizeUri(uri);
            String method = request.getMethod();
            int status = response.getStatus();
            String statusGroup = getStatusGroup(status);

            // 记录请求耗时（Timer，含百分位数）
            Timer.builder("myxhs_http_request_duration_seconds")
                    .description("HTTP 请求耗时")
                    .tag("uri", normalizedUri)
                    .tag("method", method)
                    .tag("status", String.valueOf(status))
                    .tag("status_group", statusGroup)
                    .publishPercentiles(0.5, 0.9, 0.95, 0.99) // P50/P90/P95/P99
                    // T-030: 同时输出 histogram bucket（P99/错误率占比等 histogram_quantile 查询可用）
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * URI 归一化：将路径变量替换为占位符
     * <p>
     * 规则：
     * 1. 纯数字路径段 → {id}：/api/note/12345 → /api/note/{id}
     * 2. 字母+数字混合且长度>=10 → {id}：/api/order/ORD202605150001 → /api/order/{id}
     * </p>
     * <p>
     * 为什么要归一化？
     * Prometheus 的每个唯一标签组合都是一个时间序列。
     * 如果 uri=/api/note/1, /api/note/2, ..., /api/note/1000000，
     * 就会产生 100 万个时间序列，导致 Prometheus OOM。
     * 归一化后只有 /api/note/{id} 一个时间序列。
     * </p>
     */
    private String normalizeUri(String uri) {
        if (uri == null) return "unknown";
        // 第一步：纯数字路径段替换
        String result = NUMERIC_SEGMENT.matcher(uri).replaceAll("/{id}");
        // 第二步：字母+数字混合 ID 替换（如订单号、UUID）
        result = MIXED_ID_SEGMENT.matcher(result).replaceAll("/{id}");
        return result;
    }

    /**
     * 获取状态码分组（2xx/3xx/4xx/5xx）
     */
    private String getStatusGroup(int status) {
        if (status >= 200 && status < 300) return "2xx";
        if (status >= 300 && status < 400) return "3xx";
        if (status >= 400 && status < 500) return "4xx";
        if (status >= 500) return "5xx";
        return "unknown";
    }

    /**
     * 是否排除该路径
     */
    private boolean shouldExclude(String uri) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }
}
