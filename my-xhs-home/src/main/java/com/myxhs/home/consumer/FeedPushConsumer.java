package com.myxhs.home.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.home.feign.AnalyticsFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Feed 推送消费者
 * <p>
 * 消费 FEED_TOPIC 中的笔记发布事件：
 * - 普通用户（粉丝数 < 阈值）→ 推模式：遍历粉丝列表，ZADD 到每个粉丝的收件箱
 * - 大V（粉丝数 >= 阈值）→ 拉模式：ZADD 到作者的发件箱
 * </p>
 * <p>
 * 幂等性：ZADD 天然幂等（相同 member 只更新 score），不需要额外去重。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "FEED_TOPIC",
        consumerGroup = "feed-push-consumer-group",
        maxReconsumeTimes = 3
)
public class FeedPushConsumer implements RocketMQListener<MessageExt> {

    private final StringRedisTemplate stringRedisTemplate;
    private final AnalyticsFeignClient analyticsFeignClient;

    @Value("${home.feed.big-v-threshold:100000}")
    private long bigVThreshold;

    @Value("${home.feed.inbox-max-days:7}")
    private int inboxMaxDays;

    @Value("${home.feed.inbox-max-size:500}")
    private int inboxMaxSize;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> event = JSON.parseObject(body, Map.class);

            Long noteId = ((Number) event.get("noteId")).longValue();
            Long authorId = ((Number) event.get("authorId")).longValue();
            Long publishTime = ((Number) event.get("publishTime")).longValue();

            // 判断是否大V
            boolean isBigV = checkBigV(authorId);

            if (isBigV) {
                // 拉模式：写入作者发件箱
                String outboxKey = RedisKeyConstants.FEED_OUTBOX + authorId;
                stringRedisTemplate.opsForZSet().add(outboxKey, String.valueOf(noteId), publishTime);
                stringRedisTemplate.expire(outboxKey, Duration.ofDays(inboxMaxDays));
                log.info("[Feed推送] 大V拉模式: authorId={}, noteId={}", authorId, noteId);
            } else {
                // 推模式：遍历粉丝列表，写入每个粉丝的收件箱
                pushToFollowers(authorId, noteId, publishTime);
            }

        } catch (Exception e) {
            log.error("[Feed推送] 处理失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("Feed推送失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 推模式：遍历粉丝列表，Pipeline 批量 ZADD 到每个粉丝的收件箱
     * <p>
     * 【M2 改造】Pipeline 批量写入替代逐个 EVALSHA：
     * 500 个粉丝从 500 次 Redis 往返 → 1 次（Pipeline），性能提升 ~100x。
     * </p>
     * <p>
     * 裁剪逻辑移至 FeedCleanupJob 异步执行（大部分收件箱未达到上限，实时裁剪不必要）。
     * ZADD 天然幂等，重复推送不产生重复数据。
     * </p>
     */
    private void pushToFollowers(Long authorId, Long noteId, Long publishTime) {
        String followerKey = RedisKeyConstants.FOLLOW_FANS + authorId;

        long cursor = 0;
        int batchSize = 500;
        int pushed = 0;
        int expireSeconds = inboxMaxDays * 24 * 3600;

        byte[] noteIdBytes = String.valueOf(noteId).getBytes();

        while (true) {
            Set<String> followerIds = stringRedisTemplate.opsForZSet()
                    .range(followerKey, cursor, cursor + batchSize - 1);

            if (followerIds == null || followerIds.isEmpty()) {
                break;
            }

            // Pipeline 批量 ZADD + EXPIRE（1 次网络往返）
            stringRedisTemplate.executePipelined(
                    (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                        for (String followerId : followerIds) {
                            byte[] inboxKey = (RedisKeyConstants.FEED_INBOX + followerId).getBytes();
                            connection.zSetCommands().zAdd(inboxKey, publishTime, noteIdBytes);
                            connection.keyCommands().expire(inboxKey, expireSeconds);
                        }
                        return null;
                    }
            );

            pushed += followerIds.size();
            cursor += batchSize;

            if (followerIds.size() < batchSize) {
                break;
            }
        }

        log.info("[Feed推送] 推模式完成: authorId={}, noteId={}, pushed={}", authorId, noteId, pushed);
    }

    /**
     * 判断是否大V（粉丝数 >= 阈值）
     * <p>
     * 优先从 Redis 缓存读取大V标记，缓存不存在时查 Redis 粉丝 ZSet 的 ZCARD。
     * 查询结果缓存 10 分钟。
     * <p>
     * 缓存时间设计考量：
     * - 1 小时太长：如果用户在缓存期内粉丝数跨越阈值（如 9.9万→10.1万），
     *   缓存仍显示"非大V"，导致推模式给百万粉丝写入，造成 Redis 写入风暴
     * - 10 分钟：可接受的缓存不一致窗口，最多 10 分钟内大V标记可能不准确
     * - 0（不缓存）：每次发布笔记都查 ZCARD，增加 Redis 负担
     * <p>
     * 后续优化方向：
     * - 监听粉丝数变更事件（关注/取关），实时刷新大V标记缓存
     * - 使用 Lua 脚本将 ZCARD + 缓存写入合并为原子操作
     */
    private boolean checkBigV(Long authorId) {
        String bigVKey = "myxhs:user:bigv:" + authorId;
        String cached = stringRedisTemplate.opsForValue().get(bigVKey);
        if (cached != null) {
            return "1".equals(cached);
        }

        // 缓存不存在，查 Redis 粉丝 ZSet 的 ZCARD
        String followerKey = RedisKeyConstants.FOLLOW_FANS + authorId;
        Long followerCount = stringRedisTemplate.opsForZSet().zCard(followerKey);
        boolean isBigV = followerCount != null && followerCount >= bigVThreshold;

        // 缓存结果（10 分钟 TTL，缩短缓存不一致窗口）
        stringRedisTemplate.opsForValue().set(bigVKey, isBigV ? "1" : "0", Duration.ofMinutes(10));
        return isBigV;
    }
}
