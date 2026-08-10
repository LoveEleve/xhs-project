package com.myxhs.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求日志过滤器
 * <p>
 * 职责：
 * 1. 为每个入站请求生成/透传 TraceId
 * 2. 记录请求入站日志（method + path + query + clientId）
 * 3. 记录请求出站日志（statusCode + 耗时）
 * <p>
 * 设计决策：
 * - 使用 System.nanoTime() 而非 System.currentTimeMillis() 计算耗时，因为 nanoTime 不受系统时钟调整影响
 * - TraceId 使用 32 位无横线 UUID，与全链路追踪标准对齐
 * - 入站日志在 then() 回调中补充出站信息，保证一次 filter 调用完成入站+出站日志
 * - 不记录请求体/响应体，避免大文件上传场景下的内存溢出风险
 * <p>
 * 分布式考虑：
 * - 网关多实例部署时，TraceId 由 UUID 生成，天然全局唯一，无需分布式协调
 * - 如果上游已携带 X-Trace-Id（如 CDN / 前端链路追踪），则透传而非覆盖
 */
@Slf4j
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String START_TIME_ATTR = "gatewayRequestStartTime";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod().name();
        String query = request.getURI().getRawQuery();

        // 1. 生成/透传 TraceId
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 2. 注入 MDC（Logstash JSON encoder 通过 includeMdcKeyName=traceId 提取）
        //    WebFlux 线程模型下 MDC 是 ThreadLocal，doFinally 确保清理
        MDC.put("traceId", traceId);

        // 3. 记录请求开始时间（用 nanoTime 避免时钟回拨影响）
        exchange.getAttributes().put(START_TIME_ATTR, System.nanoTime());

        // 4. 注入 TraceId 到请求 Header
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();

        // 5. 入站日志
        if (query != null && !query.isEmpty()) {
            log.info("[Gateway] >>> method={}, path={}, query={}, traceId={}",
                    method, path, query, traceId);
        } else {
            log.info("[Gateway] >>> method={}, path={}, traceId={}",
                    method, path, traceId);
        }

        final String finalTraceId = traceId;

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .then(Mono.<Void>fromRunnable(() -> {
                    // 6. 出站日志：记录响应状态码和耗时
                    Long startTimeNanos = exchange.getAttribute(START_TIME_ATTR);
                    if (startTimeNanos != null) {
                        long durationMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                        Integer statusCode = exchange.getResponse().getStatusCode() != null
                                ? exchange.getResponse().getStatusCode().value()
                                : null;
                        log.info("[Gateway] <<< method={}, path={}, status={}, duration={}ms, traceId={}",
                                method, path, statusCode, durationMs, finalTraceId);
                    }
                }))
                .doFinally(signal -> MDC.remove("traceId"));  // 7. 清理 MDC
    }

    @Override
    public int getOrder() {
        // 最早执行，记录所有请求的入站/出站日志
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
