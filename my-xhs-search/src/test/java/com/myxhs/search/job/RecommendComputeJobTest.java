package com.myxhs.search.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendComputeJobTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SetOperations<String, String> setOperations;

    @Test
    void enrichEngagementCountsShouldNotReadTCounter() throws Exception {
        RecommendComputeJob job = new RecommendComputeJob(stringRedisTemplate, jdbcTemplate);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size("myxhs:like:note:1")).thenReturn(5L);
        when(jdbcTemplate.queryForList(contains("FROM t_comment"))).thenReturn(List.of(Map.of("note_id", 1L, "comment_count", 2L)));
        when(jdbcTemplate.queryForList(contains("FROM my_xhs_analytics.t_favorite"))).thenReturn(List.of(Map.of("note_id", 1L, "favorite_count", 3L)));

        Class<?> noteFeaturesClass = Class.forName("com.myxhs.search.job.RecommendComputeJob$NoteFeatures");
        var ctor = noteFeaturesClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object feature = ctor.newInstance("生活", List.of("生活"), 0L, 0L, 0L);
        Map<Long, Object> features = new HashMap<>();
        features.put(1L, feature);

        Method method = RecommendComputeJob.class.getDeclaredMethod("enrichEngagementCounts", Map.class);
        method.setAccessible(true);
        method.invoke(job, features);

        verify(jdbcTemplate, never()).queryForList(contains("FROM t_counter"));
        assertThat(noteFeaturesClass.getDeclaredField("likeCount").trySetAccessible()).isTrue();
    }
}
