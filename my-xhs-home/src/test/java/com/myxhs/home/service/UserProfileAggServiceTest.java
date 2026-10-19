package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.UserProfileAggVO;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.feign.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户主页聚合服务单测（C-014）
 * <p>
 * 验证口径：F1 池隔离 / F2 likeCollect（String id + 分批 ≤100 + 部分成功累计 + 页上限）/
 * 503 vs 404 语义 / noteCount（String total）。
 * </p>
 */
@SuppressWarnings("unchecked")
class UserProfileAggServiceTest {

    private UserFeignClient userFeignClient;
    private AnalyticsFeignClient analyticsFeignClient;
    private ContentFeignClient contentFeignClient;
    private CounterFeignClient counterFeignClient;
    private ExecutorService batchFeignPool;
    private UserProfileAggService service;

    @BeforeEach
    void setUp() {
        userFeignClient = mock(UserFeignClient.class);
        analyticsFeignClient = mock(AnalyticsFeignClient.class);
        contentFeignClient = mock(ContentFeignClient.class);
        counterFeignClient = mock(CounterFeignClient.class);
        batchFeignPool = spy(Executors.newFixedThreadPool(4));
        service = new UserProfileAggService(userFeignClient, analyticsFeignClient,
                contentFeignClient, counterFeignClient, batchFeignPool);
    }

    private void stubUserOk() {
        when(userFeignClient.getUserPublicInfo(1L)).thenReturn(R.ok(Map.of("nickname", "nick")));
        when(analyticsFeignClient.getFollowerCount(1L)).thenReturn(R.ok(10L));
        when(analyticsFeignClient.getFollowingCount(1L)).thenReturn(R.ok(20L));
        when(analyticsFeignClient.checkRelation(anyLong(), anyLong())).thenReturn(R.ok(Collections.emptyMap()));
    }

    /** 每页 50 条 String id，total="250"（R4 Long→String） */
    private void stubNotes250() {
        when(contentFeignClient.getUserNotes(eq(1L), anyInt(), anyInt())).thenAnswer(inv -> {
            int page = inv.getArgument(1);
            int size = inv.getArgument(2);
            if (size == 10) { // notesFuture（前 10 条）
                return R.ok(Map.of("records",
                        List.of(Map.of("id", "11", "title", "t1", "coverUrl", "c1", "noteType", 0)),
                        "total", "1"));
            }
            List<Map<String, Object>> recs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                recs.add(Map.of("id", String.valueOf(page * 1000L + i))); // String id（R4）
            }
            return R.ok(Map.of("records", recs, "total", "250"));
        });
    }

    private static R<Map<String, Map<String, Long>>> countsFor(Object request) {
        Map<String, Object> req = (Map<String, Object>) request;
        List<Map<String, Object>> queries = (List<Map<String, Object>>) req.get("queries");
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Map<String, Object> q : queries) {
            result.put("1:" + q.get("targetId"), Map.of("like", 1L, "collect", 1L));
        }
        return R.ok(result);
    }

    @Test
    @DisplayName("C-014 F1：内层 future 提交 batchFeignPool")
    void poolIsolation() {
        stubUserOk();
        stubNotes250();
        when(counterFeignClient.batchGetCounts(any())).thenAnswer(inv -> countsFor(inv.getArgument(0)));

        service.getUserProfile(1L, null);

        verify(batchFeignPool, atLeastOnce()).execute(any());
    }

    @Test
    @DisplayName("C-014 F2：250 篇笔记（String id）→ 分 3 批（≤100/批）累计 like+collect=500")
    void likeCollectChunked() {
        stubUserOk();
        stubNotes250();
        when(counterFeignClient.batchGetCounts(any())).thenAnswer(inv -> countsFor(inv.getArgument(0)));

        UserProfileAggVO vo = service.getUserProfile(1L, null);

        assertThat(vo.getLikeAndCollectCount()).isEqualTo(500L);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(counterFeignClient, times(3)).batchGetCounts(captor.capture());
        List<Integer> chunkSizes = new ArrayList<>();
        for (Map<String, Object> req : captor.getAllValues()) {
            chunkSizes.add(((List<?>) req.get("queries")).size());
        }
        assertThat(chunkSizes).containsExactly(100, 100, 50);
    }

    @Test
    @DisplayName("C-014 F2：单批失败 → 部分成功累计（不归零）")
    void likeCollectPartialFailure() {
        stubUserOk();
        stubNotes250();
        AtomicInteger calls = new AtomicInteger();
        when(counterFeignClient.batchGetCounts(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new RuntimeException("counter down");
            }
            return countsFor(inv.getArgument(0));
        });

        UserProfileAggVO vo = service.getUserProfile(1L, null);

        // 批 1（100×2）+ 批 3（50×2）= 300
        assertThat(vo.getLikeAndCollectCount()).isEqualTo(300L);
    }

    @Test
    @DisplayName("不存在的用户 → null（404）；用户服务 503 → DownstreamUnavailable")
    void userNotFoundAndUnavailable() {
        when(userFeignClient.getUserPublicInfo(1L)).thenReturn(R.ok(Collections.emptyMap()));
        when(analyticsFeignClient.getFollowerCount(1L)).thenReturn(R.ok(0L));
        when(analyticsFeignClient.getFollowingCount(1L)).thenReturn(R.ok(0L));
        when(contentFeignClient.getUserNotes(eq(1L), anyInt(), anyInt()))
                .thenReturn(R.ok(Map.of("records", Collections.emptyList(), "total", "0")));
        when(counterFeignClient.batchGetCounts(any())).thenReturn(R.ok(Collections.emptyMap()));

        assertThat(service.getUserProfile(1L, null)).isNull();

        when(userFeignClient.getUserPublicInfo(1L)).thenReturn(R.fail(503, "用户服务不可用"));
        assertThatThrownBy(() -> service.getUserProfile(1L, null))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    @DisplayName("noteCount 取 String 型 total（T-119 回归）")
    void noteCountFromStringTotal() {
        stubUserOk();
        when(contentFeignClient.getUserNotes(eq(1L), anyInt(), anyInt())).thenAnswer(inv -> {
            int size = inv.getArgument(2);
            if (size == 10) {
                return R.ok(Map.of("records",
                        List.of(Map.of("id", "11", "title", "t1")), "total", "42"));
            }
            return R.ok(Map.of("records", Collections.emptyList(), "total", "42"));
        });
        when(counterFeignClient.batchGetCounts(any())).thenReturn(R.ok(Collections.emptyMap()));

        UserProfileAggVO vo = service.getUserProfile(1L, null);

        assertThat(vo.getNoteCount()).isEqualTo(42L);
    }
}
