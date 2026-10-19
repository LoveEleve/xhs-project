package com.myxhs.home.service;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.response.R;
import com.myxhs.home.dto.FeedVO;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.feign.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Feed 流服务单测（C-014）
 * <p>
 * 验证口径：G2 空流未读真实值 / F3 超时→503 / G1 卡片字段 / F5 sentinel / F1 池隔离 / 既有组装语义。
 * </p>
 */
@SuppressWarnings("unchecked")
class FeedServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private ZSetOperations<String, String> zsetOps;
    private ContentFeignClient contentFeignClient;
    private UserFeignClient userFeignClient;
    private AnalyticsFeignClient analyticsFeignClient;
    private CounterFeignClient counterFeignClient;
    private NotificationFeignClient notificationFeignClient;
    private ExecutorService batchFeignPool;
    private FeedService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        contentFeignClient = mock(ContentFeignClient.class);
        userFeignClient = mock(UserFeignClient.class);
        analyticsFeignClient = mock(AnalyticsFeignClient.class);
        counterFeignClient = mock(CounterFeignClient.class);
        notificationFeignClient = mock(NotificationFeignClient.class);
        batchFeignPool = spy(Executors.newFixedThreadPool(4));
        service = new FeedService(redisTemplate, contentFeignClient, userFeignClient,
                analyticsFeignClient, counterFeignClient, notificationFeignClient, batchFeignPool);
        ReflectionTestUtils.setField(service, "bigVThreshold", 100000L);
        ReflectionTestUtils.setField(service, "defaultPageSize", 20);
    }

    private static ZSetOperations.TypedTuple<String> tuple(String v, double score) {
        return new DefaultTypedTuple<>(v, score);
    }

    /** 无大V、无缓存：getFollowingBigVIds 走 FOLLOW_LIST 空路径 */
    private void stubNoBigV() {
        when(setOps.members(anyString())).thenReturn(null);
        when(zsetOps.reverseRange(eq(RedisKeyConstants.FOLLOW_LIST + 9L), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("C-014 G2：空 Feed 返回真实未读数（非写死 0）")
    void emptyFeedReturnsRealUnread() {
        when(zsetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());
        stubNoBigV();
        when(notificationFeignClient.getUnreadCount(9L)).thenReturn(R.ok(Map.of("total", 5)));

        FeedVO vo = service.getFollowFeed(9L, null, 20);

        assertThat(vo.getNotes()).isEmpty();
        assertThat(vo.getUnreadCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("C-014 G1：卡片填充 createdAt + score（原从未填充）")
    void cardFieldsPopulated() {
        when(zsetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("101", 1715510539000.0))));
        stubNoBigV();
        Map<String, Object> note = new HashMap<>();
        note.put("userId", 7L);
        note.put("title", "标题");
        note.put("coverUrl", "http://c");
        note.put("noteType", 0);
        note.put("createdAt", "2026-10-19T10:00:00");
        when(contentFeignClient.batchGetNoteDetail(any())).thenReturn(R.ok(Map.of("101", note)));
        when(analyticsFeignClient.batchCheckLikeStatus(anyLong(), anyInt(), anyString()))
                .thenReturn(R.ok(Collections.emptyMap()));
        when(counterFeignClient.batchGetCounts(any())).thenReturn(R.ok(Collections.emptyMap()));
        when(userFeignClient.batchGetUserPublicInfo(any())).thenReturn(R.ok(Collections.emptyMap()));
        when(notificationFeignClient.getUnreadCount(9L)).thenReturn(R.ok(Map.of("total", 0)));

        FeedVO vo = service.getFollowFeed(9L, null, 20);

        assertThat(vo.getNotes()).hasSize(1);
        assertThat(vo.getNotes().get(0).getScore()).isEqualTo(1715510539000.0);
        assertThat(vo.getNotes().get(0).getCreatedAt()).isNotNull();
        // C-014 F1：内层 future 提交 batchFeignPool（池隔离防回归）
        verify(batchFeignPool, atLeastOnce()).execute(any());
    }

    @Test
    @DisplayName("C-014 F3：笔记详情超时（future 未完成）→ 503，不再伪装空 Feed")
    void notesTimeoutDegradesTo503() {
        when(zsetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("101", 100.0))));
        stubNoBigV();
        when(contentFeignClient.batchGetNoteDetail(any())).thenAnswer(inv -> {
            Thread.sleep(5000); // 模拟下游挂起（L1 超时 3s 先触发）
            return R.ok(Collections.emptyMap());
        });

        assertThatThrownBy(() -> service.getFollowFeed(9L, null, 20))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasMessageContaining("内容服务不可用");
    }

    @Test
    @DisplayName("C-014 F5：sentinel 缓存读回——无大V关注不重扫 FOLLOW_LIST")
    void bigVEmptySentinelReadBack() {
        when(zsetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());
        when(setOps.members(anyString())).thenReturn(Set.of("-1")); // sentinel 命中
        when(notificationFeignClient.getUnreadCount(9L)).thenReturn(R.ok(Map.of("total", 0)));

        service.getFollowFeed(9L, null, 20);

        verify(zsetOps, never()).reverseRange(anyString(), anyLong(), anyLong()); // 未重扫关注列表
    }

    @Test
    @DisplayName("C-014 F5：有关注但无大V → 空结果写 sentinel 缓存（pipeline，不重扫）")
    void bigVEmptyWritesSentinel() {
        when(zsetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Collections.emptySet());
        when(setOps.members(anyString())).thenReturn(null);
        when(zsetOps.reverseRange(eq(RedisKeyConstants.FOLLOW_LIST + 9L), anyLong(), anyLong()))
                .thenReturn(Set.of("5", "6"));
        when(valueOps.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        when(zsetOps.zCard(anyString())).thenReturn(1L); // 均未达大V阈值
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(Collections.emptyList());
        when(notificationFeignClient.getUnreadCount(9L)).thenReturn(R.ok(Map.of("total", 0)));

        service.getFollowFeed(9L, null, 20);

        verify(redisTemplate, atLeastOnce()).executePipelined(any(RedisCallback.class)); // sentinel 写入走 pipeline
    }

    @Test
    @DisplayName("既有语义回归：脏成员跳过（不 500）+ 同 noteId 保高分去重")
    void dirtyMemberSkippedAndDedup() {
        when(zsetOps.reverseRangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of(tuple("abc", 200.0), tuple("101", 100.0))));
        // 大V发件箱返回同 noteId 低分 → 去重保高分
        when(setOps.members(anyString())).thenReturn(Set.of("7"));
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(new LinkedHashSet<>(List.of(tuple("101", 50.0)))));
        Map<String, Object> note = new HashMap<>();
        note.put("userId", 7L);
        note.put("title", "t");
        when(contentFeignClient.batchGetNoteDetail(any())).thenReturn(R.ok(Map.of("101", note)));
        when(analyticsFeignClient.batchCheckLikeStatus(anyLong(), anyInt(), anyString()))
                .thenReturn(R.ok(Collections.emptyMap()));
        when(counterFeignClient.batchGetCounts(any())).thenReturn(R.ok(Collections.emptyMap()));
        when(userFeignClient.batchGetUserPublicInfo(any())).thenReturn(R.ok(Collections.emptyMap()));
        when(notificationFeignClient.getUnreadCount(9L)).thenReturn(R.ok(Map.of("total", 0)));

        FeedVO vo = service.getFollowFeed(9L, null, 20);

        assertThat(vo.getNotes()).hasSize(1); // 脏成员跳过 + 去重
        assertThat(vo.getNotes().get(0).getScore()).isEqualTo(100.0); // 保高分
    }
}
