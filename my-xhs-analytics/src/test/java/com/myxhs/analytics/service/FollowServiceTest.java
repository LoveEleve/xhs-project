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

    private FollowService followService;

    private static final Long USER_ID = 1001L;
    private static final Long TARGET_USER_ID = 2001L;

    @BeforeEach
    void setUp() {
        followService = new FollowService(
                stringRedisTemplate, followSelfScript, followTargetScript,
                unfollowSelfScript, unfollowTargetScript,
                followMapper, idGeneratorUtil);
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
