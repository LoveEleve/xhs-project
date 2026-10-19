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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 网关鉴权过滤器单测（C-015）
 * <p>
 * 验证口径：白名单放行 / 401 四类 / Redis 故障 503（fail-closed 可重试）/ 通过注入（set 覆盖防伪造）/
 * C-015 F3 启动 fail-closed（空/演示密钥）。
 * </p>
 */
class GatewayAuthFilterTest {

    private static final String SECRET = "test-gateway-secret-key-at-least-32-bytes-long-1234";

    private AuthProperties authProperties;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setSecret(SECRET);
        authProperties.setWhiteList(List.of("/api/public/**"));
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        filter = new GatewayAuthFilter(authProperties, redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
        filter.initSecretKey();
    }

    private static String token(String subject, String type, String jti) {
        var builder = io.jsonwebtoken.Jwts.builder()
                .id(jti)
                .claim("type", type)
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
    }

    private static MockServerWebExchange exchange(String path, String authHeader) {
        var requestBuilder = org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(path);
        if (authHeader != null) {
            requestBuilder = requestBuilder.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return MockServerWebExchange.from(requestBuilder.build());
    }

    private static GatewayFilterChain chain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    @DisplayName("白名单路径直接放行")
    void whitelistPass() {
        GatewayFilterChain chain = chain();
        MockServerWebExchange exchange = exchange("/api/public/ping", null);

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange); // 原样透传（不 mutate）
    }

    @Test
    @DisplayName("缺 Authorization → 401")
    void missingHeader() {
        GatewayFilterChain chain = chain();
        MockServerWebExchange exchange = exchange("/api/order/1", null);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Token 类型错误（refresh 冒充）→ 401")
    void wrongType() {
        GatewayFilterChain chain = chain();
        MockServerWebExchange exchange = exchange("/api/order/1", "Bearer " + token("9", "refresh", "j1"));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("黑名单命中 → 401 已被注销")
    void blacklisted() {
        MockServerWebExchange exchange = exchange("/api/order/1", "Bearer " + token("9", "access", "j1"));
        when(valueOps.get("myxhs:user:token:blacklist:j1")).thenReturn("1");

        filter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Redis 故障 → 503（与真吊销 401 区分，可重试）")
    void redisDown503() {
        MockServerWebExchange exchange = exchange("/api/order/1", "Bearer " + token("9", "access", "j1"));
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        filter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("通过：注入 X-User-Id（set 覆盖防伪造）")
    void successInjectsIdentity() {
        // 伪造头：客户端自带 X-User-Id=666，鉴权后必须被覆盖为 token 的 sub
        var forged = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/order/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("9", "access", "j1"))
                        .header("X-User-Id", "666")
                        .build());
        when(valueOps.get(anyString())).thenReturn(null);
        GatewayFilterChain chain = chain();

        filter.filter(forged, chain).block();

        var captor = org.mockito.ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("9");
    }

    @Test
    @DisplayName("sub 为空 → 401（RV30）")
    void missingSub() {
        MockServerWebExchange exchange = exchange("/api/order/1", "Bearer " + token(null, "access", "j1"));
        when(valueOps.get(anyString())).thenReturn(null);

        filter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("C-015 F3：空/演示密钥 → 拒绝启动")
    void failClosedOnInsecureSecret() {
        AuthProperties blank = new AuthProperties();
        blank.setSecret(" ");
        GatewayAuthFilter f1 = new GatewayAuthFilter(blank, redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
        assertThatThrownBy(f1::initSecretKey).isInstanceOf(IllegalStateException.class);

        AuthProperties demo = new AuthProperties();
        demo.setSecret("MyXhs@2026#JwtSecretKey!ForTokenSign");
        GatewayAuthFilter f2 = new GatewayAuthFilter(demo, redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
        assertThatThrownBy(f2::initSecretKey).isInstanceOf(IllegalStateException.class);
    }
}
