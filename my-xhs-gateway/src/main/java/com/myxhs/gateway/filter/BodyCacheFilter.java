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

    /** 超限标记（chunked 请求无 Content-Length，靠 join 的 maxByteCount 兜底后置位 → 413） */
    private static final String BODY_TOO_LARGE_ATTR = "myxhs.body.too.large";

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
        // T-130 修复（2026-09-06 运行态复核）：无 body 的 POST/PUT/DELETE（如 block/logout）
        // 时 request.getBody() 是空 Flux，DataBufferUtils.join 返回 empty Mono，
        // flatMap 不执行 -> 请求链既不继续也不完成 -> 空响应。
        // defaultIfEmpty 提供空 body 使链路正常继续。
        // 先看 Content-Length 快速拒绝：原实现是把整个 body 拉进内存后才判 1MB，
        // 未鉴权请求也能用超大 POST 打爆网关堆（chunked 无长度时由 join 的 maxByteCount 兜底）
        long declaredLength = request.getHeaders().getContentLength();
        if (declaredLength > MAX_BODY_BYTES) {
            log.warn("[BodyCache] 请求体超限(Content-Length={} > {}): method={}, uri={}",
                    declaredLength, MAX_BODY_BYTES, request.getMethod(), request.getURI());
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }

        return DataBufferUtils.join(request.getBody(), (int) MAX_BODY_BYTES)
                .onErrorResume(ex -> {
                    if (ex instanceof org.springframework.core.io.buffer.DataBufferLimitException) {
                        log.warn("[BodyCache] 请求体超限(chunked > {}): method={}, uri={}",
                                MAX_BODY_BYTES, request.getMethod(), request.getURI());
                        exchange.getAttributes().put(BODY_TOO_LARGE_ATTR, Boolean.TRUE);
                        return Mono.just(exchange.getResponse().bufferFactory().wrap(new byte[0]));
                    }
                    log.warn("[BodyCache] 请求体读取异常，按空body降级: {}", ex.getMessage());
                    return Mono.just(exchange.getResponse().bufferFactory().wrap(new byte[0]));
                })
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    if (Boolean.TRUE.equals(exchange.getAttributes().get(BODY_TOO_LARGE_ATTR))) {
                        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
                        return exchange.getResponse().setComplete();
                    }
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                                        if (bytes.length > MAX_BODY_BYTES) {
                        log.warn("[BodyCache] 请求体超限({} > {}): method={}, uri={}",
                                bytes.length, MAX_BODY_BYTES, request.getMethod(), request.getURI());
                        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
                        return exchange.getResponse().setComplete();
                    }
                    exchange.getAttributes().put(CACHED_BODY_ATTR, bytes);
                    ServerHttpRequest mutated = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory()
                                    .wrap(bytes));
                        }
                    };
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    @Override
    public int getOrder() {
        // 1100：在鉴权(+1000)之后、染色(+1200)/HMAC(+1500)之前。
        // 原来用 HIGHEST_PRECEDENCE 会在鉴权前读取未认证请求的 body（放大攻击面），
        // 而 body 的需求方只有 HMAC 验签（+1500）与下游转发。
        return 1100;
    }
}
