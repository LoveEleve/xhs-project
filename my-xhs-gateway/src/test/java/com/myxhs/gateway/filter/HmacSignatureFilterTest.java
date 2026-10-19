package com.myxhs.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.gateway.config.AuthProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HMAC 签名过滤器单测（C-015）
 * <p>
 * 验证口径：关闭透传 / 缺头/坏时间戳/过期/无身份/密钥缺失 → 403 / Redis 故障 fail-closed /
 * 先验签后占 nonce（重放拒绝）/ 验签通过放行。
 * </p>
 */
class HmacSignatureFilterTest {

    private static final String USER_SECRET = "per-session-secret-abc";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private HmacSignatureFilter filter;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setHmacEnabled(true);
        props.setHmacWhiteList(Collections.emptyList());
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        filter = new HmacSignatureFilter(redisTemplate, props, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static GatewayFilterChain chain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private static MockServerWebExchange request(String path, List<String[]> headers) {
        var b = MockServerHttpRequest.get(path);
        for (String[] h : headers) {
            b = b.header(h[0], h[1]);
        }
        return MockServerWebExchange.from(b.build());
    }

    private static String sign(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("HMAC 关闭 → 整条链跳过（默认生产口径）")
    void disabledPassesThrough() {
        AuthProperties off = new AuthProperties();
        off.setHmacEnabled(false);
        HmacSignatureFilter disabled = new HmacSignatureFilter(redisTemplate, off, new ObjectMapper(), new SimpleMeterRegistry());
        MockServerWebExchange exchange = request("/api/x", List.of());
        GatewayFilterChain chain = chain();

        disabled.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("非白名单缺签名头 → 403")
    void missingHeaders() {
        MockServerWebExchange exchange = request("/api/order", List.of());
        filter.filter(exchange, chain()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("timestamp 格式错/超 5min → 403")
    void badAndExpiredTimestamp() {
        MockServerWebExchange bad = request("/api/order", List.of(
                new String[]{"X-Timestamp", "abc"}, new String[]{"X-Nonce", "n1"}, new String[]{"X-Signature", "s"}));
        filter.filter(bad, chain()).block();
        assertThat(bad.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        MockServerWebExchange expired = request("/api/order", List.of(
                new String[]{"X-Timestamp", String.valueOf(System.currentTimeMillis() - 10 * 60 * 1000)},
                new String[]{"X-Nonce", "n1"}, new String[]{"X-Signature", "s"}));
        filter.filter(expired, chain()).block();
        assertThat(expired.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("缺 X-User-Id（未鉴权路径误入）→ 403")
    void noUserIdentity() {
        MockServerWebExchange exchange = request("/api/order", List.of(
                new String[]{"X-Timestamp", String.valueOf(System.currentTimeMillis())},
                new String[]{"X-Nonce", "n1"}, new String[]{"X-Signature", "s"}));
        filter.filter(exchange, chain()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("per-session 密钥不存在（过期）→ 403")
    void secretMissing() {
        when(valueOps.get("myxhs:user:hmac:secret:9")).thenReturn(null);
        MockServerWebExchange exchange = request("/api/order", List.of(
                new String[]{"X-Timestamp", String.valueOf(System.currentTimeMillis())},
                new String[]{"X-Nonce", "n1"}, new String[]{"X-Signature", "s"},
                new String[]{"X-User-Id", "9"}));
        filter.filter(exchange, chain()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Redis 读密钥异常 → 403（fail-closed，不因 Redis 故障绕过验签）")
    void redisFailureFailClosed() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));
        MockServerWebExchange exchange = request("/api/order", List.of(
                new String[]{"X-Timestamp", String.valueOf(System.currentTimeMillis())},
                new String[]{"X-Nonce", "n1"}, new String[]{"X-Signature", "s"},
                new String[]{"X-User-Id", "9"}));
        filter.filter(exchange, chain()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("验签通过 + nonce 首次 → 放行；nonce 重放 → 403")
    void validAndReplay() throws Exception {
        long ts = System.currentTimeMillis();
        String signStr = "GET|/api/order||" + ts + "|n1|";
        String sig = sign(USER_SECRET, signStr);
        when(valueOps.get("myxhs:user:hmac:secret:9")).thenReturn(USER_SECRET);
        when(redisTemplate.execute(any(), anyList(), anyString())).thenReturn(true);

        MockServerWebExchange ok = request("/api/order", List.of(
                new String[]{"X-Timestamp", String.valueOf(ts)}, new String[]{"X-Nonce", "n1"},
                new String[]{"X-Signature", sig}, new String[]{"X-User-Id", "9"}));
        GatewayFilterChain chain = chain();
        filter.filter(ok, chain).block();
        verify(chain).filter(ok);

        // 重放：nonce 已存在
        when(redisTemplate.execute(any(), anyList(), anyString())).thenReturn(false);
        MockServerWebExchange replay = request("/api/order", List.of(
                new String[]{"X-Timestamp", String.valueOf(ts)}, new String[]{"X-Nonce", "n1"},
                new String[]{"X-Signature", sig}, new String[]{"X-User-Id", "9"}));
        filter.filter(replay, chain()).block();
        assertThat(replay.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
