package com.myxhs.im.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.service.ChatService;
import com.myxhs.im.service.OnlineRouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM REST 控制器单测（C-013）：ticket 签发（jti 登记）/ 启动自检 fail-closed / 限流注解核验。
 */
@SuppressWarnings("unchecked")
class ImControllerTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes-long-1234";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ImController controller;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        controller = new ImController(mock(ChatService.class), mock(ImWebSocketHandler.class),
                mock(OnlineRouteService.class), mock(AccessTokenGuard.class), redisTemplate);
        ReflectionTestUtils.setField(controller, "jwtSecret", SECRET);
    }

    @Test
    @DisplayName("ticket 签发：JWT(ws_ticket) + jti 登记 5min（GETDEL 一次性）")
    void createTicketRegistersJti() {
        var result = controller.createTicket(9L);

        String ticket = (String) result.getData().get("ticket");
        assertThat(ticket).isNotBlank();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), eq("9"), eq(5L), eq(TimeUnit.MINUTES));
        assertThat(keyCaptor.getValue()).startsWith("myxhs:im:ticket:");
    }

    @Test
    @DisplayName("启动自检 fail-closed：空 / 演示密钥 → 拒绝启动；安全密钥 → 通过")
    void validateJwtSecretFailClosed() {
        ReflectionTestUtils.setField(controller, "jwtSecret", "");
        assertThatThrownBy(controller::validateJwtSecret).isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(controller, "jwtSecret", "MyXhs@2026#JwtSecretKey!ForTokenSign");
        assertThatThrownBy(controller::validateJwtSecret).isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(controller, "jwtSecret", SECRET);
        controller.validateJwtSecret(); // 不抛
    }

    @Test
    @DisplayName("C-013 §7-5：四端点限流注解落位")
    void rateLimitsPresent() throws Exception {
        assertRateLimit("createTicket");
        assertRateLimit("getConversations");
        assertRateLimit("getMessages");
        assertRateLimit("getUnreadCount");
    }

    private void assertRateLimit(String methodName) throws Exception {
        Method m = java.util.Arrays.stream(ImController.class.getMethods())
                .filter(mm -> mm.getName().equals(methodName)).findFirst().orElseThrow();
        assertThat(m.getAnnotation(RateLimit.class)).as(methodName + " 应有 @RateLimit").isNotNull();
    }
}
