package com.myxhs.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * T-009/010/011: 请求体缓存过滤器——供 HMAC 签名校验读取 body 计算摘要。
 * <p>
 * 在 HMAC 过滤器（HIGHEST_PRECEDENCE + 1000）之前执行，把 body 读入内存并缓存到
 * exchange attribute，同时重建请求体供下游使用。限制 body 大小（1MB）防内存打爆。
 */
@Component
@lombok.extern.slf4j.Slf4j
public class BodyCacheFilter implements GlobalFilter, Ordered {

    /** 缓存 body 的 exchange attribute key */
    public static final String CACHED_BODY_ATTR = "myxhs.cached.request.body";

    private static final long MAX_BODY_BYTES = 1024 * 1024;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 仅缓存有 body 的请求（POST/PUT/PATCH/DELETE）
        if (!"POST".equalsIgnoreCase(request.getMethod().name())
                && !"PUT".equalsIgnoreCase(request.getMethod().name())
                && !"PATCH".equalsIgnoreCase(request.getMethod().name())
                && !"DELETE".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }

        // T-041 修复（2026-08-13）：multipart 请求跳过缓存/重建——
        // 原实现 >1MB 截断为空 body，导致下游 multipart 解析 EOF（500），>1MB 图片上传不可用。
        // multipart 的 HMAC 签名统一按 bodyHash=""（cachedBody=null），与现有测试客户端一致。
        String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return chain.filter(exchange);
        }

        // 已有缓存（重入/测试）直接放行
        if (exchange.getAttribute(CACHED_BODY_ATTR) != null) {
            return chain.filter(exchange);
        }

        // T-020: body 读取异常按空 body 降级（签名校验会 403，避免 500/连接异常）
        return DataBufferUtils.join(request.getBody())
                .onErrorResume(ex -> {
                    log.warn("[BodyCache] 请求体读取异常，按空body降级: {}", ex.getMessage());
                    exchange.getAttributes().put(CACHED_BODY_ATTR, new byte[0]);
                    return Mono.empty();
                })
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    if (bytes.length > MAX_BODY_BYTES) {
                        bytes = new byte[0]; // 超限按空 body 处理（签名校验会失败，防内存打爆）
                    }
                    return bytes;
                })
                .flatMap(bytes -> {
                    exchange.getAttributes().put(CACHED_BODY_ATTR, bytes);
                    ServerHttpRequest mutated = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory()
                                    .wrap(bytes));
                        }
                    };
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // 最先执行，确保 HMAC 之前 body 已缓存
    }
}
