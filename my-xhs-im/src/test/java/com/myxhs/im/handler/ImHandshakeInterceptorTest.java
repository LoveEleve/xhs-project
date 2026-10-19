package com.myxhs.im.handler;

import com.myxhs.common.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS 握手拦截器单测（C-013）：票据三关（类型/jti 一次性/subject 一致）+ traceId 注入。
 */
@SuppressWarnings("unchecked")
class ImHandshakeInterceptorTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long-1234";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ImHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        interceptor = new ImHandshakeInterceptor(redisTemplate);
        ReflectionTestUtils.setField(interceptor, "jwtSecret", SECRET);
    }

    private static ServletServerHttpRequest request(String ticket) {
        ServletServerHttpRequest req = mock(ServletServerHttpRequest.class);
        HttpServletRequest servlet = mock(HttpServletRequest.class);
        when(req.getServletRequest()).thenReturn(servlet);
        when(servlet.getParameter("ticket")).thenReturn(ticket);
        return req;
    }

    private String wsTicket(String subject) {
        return JwtUtil.generateToken(subject, "ws_ticket", 5 * 60 * 1000L, SECRET);
    }

    @Test
    @DisplayName("合法票据：jti 一次性消费 + subject 一致 → userId/traceId 入 attributes")
    void validTicket() {
        String ticket = wsTicket("9");
        when(valueOps.getAndDelete(anyString())).thenReturn("9");
        Map<String, Object> attrs = new HashMap<>();

        boolean ok = interceptor.beforeHandshake(request(ticket), null, null, attrs);

        assertThat(ok).isTrue();
        assertThat(attrs.get("userId")).isEqualTo(9L);
        assertThat(attrs.get("traceId")).isNotNull();
    }

    @Test
    @DisplayName("类型错（access token 冒充）→ 拒绝")
    void wrongTypeRejected() {
        String access = JwtUtil.generateToken("9", "access", 5 * 60 * 1000L, SECRET);

        assertThat(interceptor.beforeHandshake(request(access), null, null, new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("jti 已被消费（重放）→ 拒绝")
    void consumedJtiRejected() {
        String ticket = wsTicket("9");
        when(valueOps.getAndDelete(anyString())).thenReturn(null);

        assertThat(interceptor.beforeHandshake(request(ticket), null, null, new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("subject 与登记不一致 → 拒绝")
    void subjectMismatchRejected() {
        String ticket = wsTicket("9");
        when(valueOps.getAndDelete(anyString())).thenReturn("8");

        assertThat(interceptor.beforeHandshake(request(ticket), null, null, new HashMap<>())).isFalse();
    }

    @Test
    @DisplayName("缺 ticket / 非法票据 → 拒绝")
    void missingOrBadTicket() {
        assertThat(interceptor.beforeHandshake(request(null), null, null, new HashMap<>())).isFalse();
        assertThat(interceptor.beforeHandshake(request("not-a-jwt"), null, null, new HashMap<>())).isFalse();
    }
}
