package com.myxhs.gateway.filter;

import com.myxhs.gateway.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BodyCache 过滤器单测（C-015）
 * <p>
 * 验证口径：F1 HMAC 关闭时短路（不读 body——原无条件缓冲所有写请求是纯开销 + 1MB 上限副作用）；
 * 开启时缓存/装饰/multipart 跳过/超限 413。
 * </p>
 */
class BodyCacheFilterTest {

    private static GatewayFilterChain chain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private static AuthProperties props(boolean hmacEnabled) {
        AuthProperties p = new AuthProperties();
        p.setHmacEnabled(hmacEnabled);
        return p;
    }

    @Test
    @DisplayName("C-015 F1：HMAC 关闭 → 直接放行，body 不读不缓存")
    void hmacDisabledShortCircuits() {
        BodyCacheFilter filter = new BodyCacheFilter(props(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/order").contentType(MediaType.APPLICATION_JSON).body("payload"));
        GatewayFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange); // 原样透传（未 mutate/未缓冲）
        assertThat((Object) exchange.getAttribute(BodyCacheFilter.CACHED_BODY_ATTR)).isNull();
    }

    @Test
    @DisplayName("HMAC 开启 + GET → 不缓存（无 body 语义）")
    void getPassThrough() {
        BodyCacheFilter filter = new BodyCacheFilter(props(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").build());
        GatewayFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("HMAC 开启 + POST → body 缓存 + 下游可重读（装饰器）")
    void postCachesAndReplays() {
        BodyCacheFilter filter = new BodyCacheFilter(props(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/order").contentType(MediaType.APPLICATION_JSON).body("hello"));
        GatewayFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        var captor = org.mockito.ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        ServerWebExchange mutated = captor.getValue();
        byte[] cached = mutated.getAttribute(BodyCacheFilter.CACHED_BODY_ATTR);
        assertThat(new String(cached, StandardCharsets.UTF_8)).isEqualTo("hello");

        var buffer = DataBufferUtils.join(mutated.getRequest().getBody()).block();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    @DisplayName("multipart 跳过缓存（T-041：>1MB 上传不被截断）")
    void multipartSkipped() {
        BodyCacheFilter filter = new BodyCacheFilter(props(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/upload").contentType(MediaType.MULTIPART_FORM_DATA).body("--x"));
        GatewayFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat((Object) exchange.getAttribute(BodyCacheFilter.CACHED_BODY_ATTR)).isNull();
    }

    @Test
    @DisplayName("Content-Length 超限 → 413（快速拒绝，不读 body）")
    void tooLargeRejected() {
        BodyCacheFilter filter = new BodyCacheFilter(props(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/order")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(2 * 1024 * 1024))
                        .body("x"));
        GatewayFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(chain, never()).filter(any());
    }
}
