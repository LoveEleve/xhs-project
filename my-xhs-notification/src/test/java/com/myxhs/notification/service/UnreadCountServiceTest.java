package com.myxhs.notification.service;

import com.myxhs.notification.dto.UnreadCountVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未读计数服务单测（C-012）
 * <p>
 * 验证口径：双键 Lua（{u:id} 同 tag 单 slot）、防负/脏值安全、forceSetUnread 补 30d TTL（§7-3）。
 * </p>
 */
@SuppressWarnings("unchecked")
class UnreadCountServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private UnreadCountService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        service = new UnreadCountService(redisTemplate);
    }

    @Test
    @DisplayName("incrementUnread：双键同 {u:id} tag 单脚本（total + type）")
    void incrementUsesTaggedDoubleKey() {
        service.incrementUnread(9L, 1);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(),
                anyString(), anyString());
        List<String> keys = keysCaptor.getValue();
        assertThat(keys).containsExactly("myxhs:notification:unread:{u:9}", "myxhs:notification:unread:type:{u:9}");
        assertThat(keys.get(0)).contains("{u:9}");
        assertThat(keys.get(1)).contains("{u:9}");
    }

    @Test
    @DisplayName("decrementUnread：单脚本（防负）")
    void decrementUsesScript() {
        service.decrementUnread(9L, 2);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), eq("2"));
    }

    @Test
    @DisplayName("resetUnread：双键删除")
    void resetDeletesBoth() {
        service.resetUnread(9L);
        verify(redisTemplate).delete("myxhs:notification:unread:{u:9}");
        verify(redisTemplate).delete("myxhs:notification:unread:type:{u:9}");
    }

    @Test
    @DisplayName("resetUnreadByType：静态脚本原子归零")
    void resetByTypeUsesScript() {
        service.resetUnreadByType(9L, 3);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), eq("3"));
    }

    @Test
    @DisplayName("getUnreadCount：解析 total + type；脏值/负值安全")
    void getUnreadCountDirtySafe() {
        when(valueOps.get("myxhs:notification:unread:{u:9}")).thenReturn("5");
        when(hashOps.entries("myxhs:notification:unread:type:{u:9}"))
                .thenReturn(Map.of("1", "2", "2", "3", "3", "abc", "4", "-1"));

        UnreadCountVO vo = service.getUnreadCount(9L);

        assertThat(vo.getTotal()).isEqualTo(5);
        assertThat(vo.getDetails()).containsEntry(1, 2).containsEntry(2, 3);
        assertThat(vo.getDetails()).doesNotContainKey(3); // 脏值跳过
        assertThat(vo.getDetails()).doesNotContainKey(4); // 负值归 0 不展示
    }

    @Test
    @DisplayName("C-012 §7-3：forceSetUnread 补 30d TTL（双键）")
    void forceSetUnreadAddsTtl() {
        service.forceSetUnread(9L, 2, Map.of(1, 2));

        verify(valueOps).set("myxhs:notification:unread:{u:9}", "2");
        verify(hashOps).putAll(eq("myxhs:notification:unread:type:{u:9}"), any());
        verify(redisTemplate).expire("myxhs:notification:unread:{u:9}", Duration.ofDays(30));
        verify(redisTemplate).expire("myxhs:notification:unread:type:{u:9}", Duration.ofDays(30));
    }
}
