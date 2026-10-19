package com.myxhs.im.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线路由服务单测（C-013）
 * <p>
 * 验证口径：register 单 Lua 原子（F2——返回旧值供踢线）；unregister/renew 单键脚本（F5：online 键族已移除）；
 * 键带 {u:id} tag（集群单 slot）。
 * </p>
 */
@SuppressWarnings("unchecked")
class OnlineRouteServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private OnlineRouteService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new OnlineRouteService(redisTemplate);
        ReflectionTestUtils.setField(service, "heartbeatInterval", 30000L);
    }

    @Test
    @DisplayName("C-013 F2：registerRoute 单脚本原子——返回旧路由（踢线依赖）")
    void registerReturnsPrevious() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn("old-instance");

        String previous = service.registerRoute(9L);

        assertThat(previous).isEqualTo("old-instance");
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(),
                anyString(), eq("90"));
        assertThat(keysCaptor.getValue()).containsExactly("myxhs:im:route:{u:9}");
        assertThat(keysCaptor.getValue().get(0)).contains("{u:9}");
    }

    @Test
    @DisplayName("registerRoute：此前不在线 → 旧值为 null")
    void registerNoPrevious() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(null);
        assertThat(service.registerRoute(9L)).isNull();
    }

    @Test
    @DisplayName("C-013 F5：unregister 单键 Lua（online 键已移除）")
    void unregisterSingleKey() {
        service.unregisterRoute(9L);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), anyString());
        assertThat(keysCaptor.getValue()).containsExactly("myxhs:im:route:{u:9}");
    }

    @Test
    @DisplayName("renew：单键 EXPIRE（90s = 3×心跳）")
    void renewSingleKey() {
        service.renewRoute(9L);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), eq("90"));
        assertThat(keysCaptor.getValue()).containsExactly("myxhs:im:route:{u:9}");
    }

    @Test
    @DisplayName("getRoute：读路由键")
    void getRoute() {
        when(valueOps.get("myxhs:im:route:{u:9}")).thenReturn("s1");
        assertThat(service.getRoute(9L)).isEqualTo("s1");
    }
}
