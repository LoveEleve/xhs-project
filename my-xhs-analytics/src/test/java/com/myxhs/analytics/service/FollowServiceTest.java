package com.myxhs.analytics.service;

import com.myxhs.analytics.dto.response.FollowVO;
import com.myxhs.analytics.entity.Follow;
import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FollowServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private DefaultRedisScript<Long> followSelfScript;
    @Mock
    private DefaultRedisScript<Long> followTargetScript;
    @Mock
    private DefaultRedisScript<Long> unfollowSelfScript;
    @Mock
    private DefaultRedisScript<Long> unfollowTargetScript;
    @Mock
    private FollowMapper followMapper;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private com.myxhs.analytics.feign.UserFeignClient userFeignClient;

    private FollowService followService;

    private static final Long USER_ID = 1001L;
    private static final Long TARGET_USER_ID = 2001L;

    @BeforeEach
    void setUp() {
        followService = new FollowService(
                stringRedisTemplate, followSelfScript, followTargetScript,
                unfollowSelfScript, unfollowTargetScript,
                 followMapper, idGeneratorUtil,
                 mock(org.apache.rocketmq.spring.core.RocketMQTemplate.class), userFeignClient);
        when(userFeignClient.userExists(TARGET_USER_ID))
                .thenReturn(com.myxhs.common.response.R.ok(true));
    }

    @Test
    @DisplayName("关注用户成功")
    void followSuccess() {
        when(stringRedisTemplate.execute(eq(followSelfScript), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        when(stringRedisTemplate.execute(eq(followTargetScript), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        when(idGeneratorUtil.nextId()).thenReturn(30001L);
        when(followMapper.insert(any(Follow.class))).thenReturn(1);

        followService.follow(USER_ID, TARGET_USER_ID);

        verify(stringRedisTemplate).execute(eq(followSelfScript), anyList(), anyString(), anyString());
        verify(stringRedisTemplate).execute(eq(followTargetScript), anyList(), anyString(), anyString());
        verify(followMapper).insert(any(Follow.class));
    }

    @Test
    @DisplayName("取关用户成功")
    void unfollowSuccess() {
        when(stringRedisTemplate.execute(eq(unfollowSelfScript), anyList(), anyString()))
                .thenReturn(1L);
        when(stringRedisTemplate.execute(eq(unfollowTargetScript), anyList(), anyString()))
                .thenReturn(1L);
        when(followMapper.deleteByUserIdAndFollowUserId(USER_ID, TARGET_USER_ID)).thenReturn(1);

        followService.unfollow(USER_ID, TARGET_USER_ID);

        verify(stringRedisTemplate).execute(eq(unfollowSelfScript), anyList(), anyString());
        verify(stringRedisTemplate).execute(eq(unfollowTargetScript), anyList(), anyString());
        verify(followMapper).deleteByUserIdAndFollowUserId(USER_ID, TARGET_USER_ID);
    }

    @Test
    @DisplayName("获取关注列表成功")
    void getFollowingListSuccess() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ZSetOperations.TypedTuple<String> tuple1 =
                new DefaultTypedTuple<>("2001", (double) System.currentTimeMillis());
        ZSetOperations.TypedTuple<String> tuple2 =
                new DefaultTypedTuple<>("2002", (double) System.currentTimeMillis() - 1000);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(tuple1);
        tuples.add(tuple2);

        when(zSetOperations.reverseRangeWithScores(startsWith("myxhs:follow:list:"), eq(0L), eq(9L)))
                .thenReturn(tuples);

        List<Object> pipelineResults = Arrays.asList(1.0, null);
        when(stringRedisTemplate.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenReturn(pipelineResults);

        List<FollowVO> result = followService.getFollowingList(USER_ID, 1, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getUserId()).isEqualTo(2001L);
        assertThat(result.get(0).getIsFollowBack()).isTrue();
        assertThat(result.get(1).getUserId()).isEqualTo(2002L);
        assertThat(result.get(1).getIsFollowBack()).isFalse();
    }

    // ==================== C-009 D1：粉丝侧对账证据判别（颤振根治） ====================

    @Test
    @DisplayName("C-009 D1：W1（follow+Step B失败）→ 证据=仍关注 → ZADD 补员修 Redis（不删行）")
    void repairFollower_whenRealRelation_repairsRedisNotDelete() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        // B=2001 的粉丝 ZSet：缺 3001（Step B 失败），有 3002/3003（redis=2, mysql=3 → 不触发双源守卫）
        Set<ZSetOperations.TypedTuple<String>> fans = new LinkedHashSet<>();
        fans.add(new DefaultTypedTuple<>("3002", 1.0));
        fans.add(new DefaultTypedTuple<>("3003", 2.0));
        when(zSetOperations.rangeWithScores(eq("myxhs:follow:fans:{2001}"), eq(0L), eq(-1L))).thenReturn(fans);

        when(followMapper.selectFollowerRowsByUserId(2001L)).thenReturn(List.of(
                followRow(3001L, 2001L), followRow(3002L, 2001L), followRow(3003L, 2001L)));

        // 证据：3001 的 FOLLOW_LIST 含 2001 → 真实关系（Step B 失败）
        when(zSetOperations.score("myxhs:follow:list:{3001}", "2001")).thenReturn(3.0);

        String result = followService.repairFollowerRelationships(2001L);

        assertThat(result).contains("Redis修复1条");
        verify(zSetOperations).add(eq("myxhs:follow:fans:{2001}"), eq("3001"), anyDouble());
        verify(followMapper, never()).deleteByUserIdAndFollowUserId(3001L, 2001L);
    }

    @Test
    @DisplayName("C-009 D1：U1（unfollow+Step B失败）→ 证据=未关注 → ZREM 清残留（不插行）")
    void repairFollower_whenUnfollowResidue_removesFromRedis() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        // 粉丝 ZSet 残留 3001（unfollow 的 Step B 失败）+ 3002/3003（redis=3, mysql=2）
        Set<ZSetOperations.TypedTuple<String>> fans = new LinkedHashSet<>();
        fans.add(new DefaultTypedTuple<>("3001", 0.5));
        fans.add(new DefaultTypedTuple<>("3002", 1.0));
        fans.add(new DefaultTypedTuple<>("3003", 2.0));
        when(zSetOperations.rangeWithScores(eq("myxhs:follow:fans:{2001}"), eq(0L), eq(-1L))).thenReturn(fans);

        when(followMapper.selectFollowerRowsByUserId(2001L)).thenReturn(List.of(
                followRow(3002L, 2001L), followRow(3003L, 2001L)));

        // 证据：3001 的列表在、成员不在 → 确定未关注（残留）
        when(zSetOperations.score("myxhs:follow:list:{3001}", "2001")).thenReturn(null);
        when(stringRedisTemplate.hasKey("myxhs:follow:list:{3001}")).thenReturn(true);

        String result = followService.repairFollowerRelationships(2001L);

        assertThat(result).contains("Redis修复1条");
        verify(zSetOperations).remove(eq("myxhs:follow:fans:{2001}"), eq("3001"));
        verify(followMapper, never()).insert(any(Follow.class));
    }

    @Test
    @DisplayName("C-009 D1：U2（真取关+删库失败）→ 证据=未关注 → 删 MySQL 行（现状语义保留）")
    void repairFollower_whenOrphanRow_deletesMysqlRow() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        Set<ZSetOperations.TypedTuple<String>> fans = new LinkedHashSet<>();
        fans.add(new DefaultTypedTuple<>("3002", 1.0));
        fans.add(new DefaultTypedTuple<>("3003", 2.0));
        when(zSetOperations.rangeWithScores(eq("myxhs:follow:fans:{2001}"), eq(0L), eq(-1L))).thenReturn(fans);

        when(followMapper.selectFollowerRowsByUserId(2001L)).thenReturn(List.of(
                followRow(3001L, 2001L), followRow(3002L, 2001L), followRow(3003L, 2001L)));

        when(zSetOperations.score("myxhs:follow:list:{3001}", "2001")).thenReturn(null);
        when(stringRedisTemplate.hasKey("myxhs:follow:list:{3001}")).thenReturn(true);

        String result = followService.repairFollowerRelationships(2001L);

        assertThat(result).contains("清孤儿行1条");
        verify(followMapper).deleteByUserIdAndFollowUserId(3001L, 2001L);
        verify(zSetOperations, never()).add(eq("myxhs:follow:fans:{2001}"), eq("3001"), anyDouble());
    }

    @Test
    @DisplayName("C-009 D1：证据不可判（对侧列表疑似丢失）→ 保守跳过（不删不建）")
    void repairFollower_whenEvidenceUnknown_skips() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        Set<ZSetOperations.TypedTuple<String>> fans = new LinkedHashSet<>();
        fans.add(new DefaultTypedTuple<>("3002", 1.0));
        fans.add(new DefaultTypedTuple<>("3003", 2.0));
        when(zSetOperations.rangeWithScores(eq("myxhs:follow:fans:{2001}"), eq(0L), eq(-1L))).thenReturn(fans);

        when(followMapper.selectFollowerRowsByUserId(2001L)).thenReturn(List.of(
                followRow(3001L, 2001L), followRow(3002L, 2001L), followRow(3003L, 2001L)));

        // 证据不可判：3001 的列表 key 不存在（疑似丢失）+ MySQL 有行
        when(zSetOperations.score("myxhs:follow:list:{3001}", "2001")).thenReturn(null);
        when(stringRedisTemplate.hasKey("myxhs:follow:list:{3001}")).thenReturn(false);
        when(followMapper.selectFollowUserIdsByUserId(3001L)).thenReturn(List.of(2001L));

        String result = followService.repairFollowerRelationships(2001L);

        assertThat(result).contains("无需修复");
        verify(followMapper, never()).deleteByUserIdAndFollowUserId(3001L, 2001L);
        verify(zSetOperations, never()).add(eq("myxhs:follow:fans:{2001}"), eq("3001"), anyDouble());
    }

    private Follow followRow(Long userId, Long followUserId) {
        Follow row = new Follow();
        row.setUserId(userId);
        row.setFollowUserId(followUserId);
        row.setCreatedAt(java.time.LocalDateTime.now());
        return row;
    }

    @Test
    @DisplayName("查询关注关系-已关注")
    void checkFollowRelationTrue() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score(startsWith("myxhs:follow:list:"), eq("2001")))
                .thenReturn(1.0);

        boolean result = followService.isFollowing(USER_ID, TARGET_USER_ID);

        assertThat(result).isTrue();
        verify(zSetOperations).score(startsWith("myxhs:follow:list:"), eq("2001"));
    }
}
