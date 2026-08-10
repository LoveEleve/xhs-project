package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.FavoriteEvent;
import com.myxhs.analytics.entity.Favorite;
import com.myxhs.analytics.mapper.FavoriteMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 收藏/取消收藏统一消费者 — 保证 FAVORITE/UNFAVORITE 同组消费顺序
 * <p>
 * 修复前：FavoriteConsumer(favorite-consumer-group) 与 UnfavoriteConsumer(unfavorite-consumer-group)
 * 分属不同 consumer group——RocketMQ 不保证跨 group 消费顺序→UNFAVORITE先到FAVORITE后到→
 * 最终 DB 显示收藏但实际用户已取消（与 B11 Like/Unlike 同一问题）。
 * <p>
 * 修复后：统一 consumer group + actionTime 版本号防乱序（Redis 记录最后处理版本，
 * 旧事件跳过），版本 key 含 userId 维度避免跨用户覆盖。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "FAVORITE||UNFAVORITE",
        consumerGroup = "favorite-unlike-consumer-group",
        maxReconsumeTimes = 3
)
public class FavoriteUnlikeConsumer implements RocketMQListener<MessageExt> {

    private final FavoriteMapper favoriteMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String VERSION_PREFIX = "analytics:event:version:favorite:";
    private static final long VERSION_TTL_HOURS = 24;

    /** 原子版本检查+更新 Lua 脚本：GET→比较→SET 在单个原子操作中完成（非final，避免被 RequiredArgsConstructor 注入） */
    private org.springframework.data.redis.core.script.DefaultRedisScript<Long> versionCheckScript
            = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "local current = redis.call('get', KEYS[1]) " +
                "local newVersion = tonumber(ARGV[1]) " +
                "if current and tonumber(current) >= newVersion then return 0 end " +
                "redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
                "return 1",
                Long.class);

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            FavoriteEvent event = objectMapper.readValue(message, FavoriteEvent.class);
            String versionKey = null;

            // 原子版本号防乱序：Lua GET+compare+SET 原子操作
            if (event.getActionTime() != null) {
                versionKey = VERSION_PREFIX + event.getUserId() + ":" + event.getNoteId();
                Long passed = stringRedisTemplate.execute(versionCheckScript,
                        java.util.List.of(versionKey),
                        String.valueOf(event.getActionTime()),
                        String.valueOf(VERSION_TTL_HOURS * 3600));
                if (passed == null || passed == 0) {
                    log.debug("[FavoriteUnlike] 跳过旧事件: userId={}, noteId={}, actionTime={}",
                            event.getUserId(), event.getNoteId(), event.getActionTime());
                    return;
                }
            }

            if ("FAVORITE".equals(event.getAction())) {
                handleFavorite(event);
            } else {
                handleUnfavorite(event);
            }
        } catch (Exception e) {
            log.error("[FavoriteUnlike] 消费失败: {}", new String(msg.getBody(), StandardCharsets.UTF_8), e);
            // 业务失败→删除版本号(Lua已SET),让MQ重试可重新处理
            if (versionKey != null) {
                try { stringRedisTemplate.delete(versionKey); } catch (Exception ignored) {}
            }
            throw new RuntimeException("FavoriteUnlike消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private void handleFavorite(FavoriteEvent event) {
        Favorite favorite = new Favorite();
        favorite.setId(idGeneratorUtil.nextId());
        favorite.setUserId(event.getUserId());
        favorite.setNoteId(event.getNoteId());
        if (event.getActionTime() != null) {
            favorite.setCreatedAt(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.getActionTime()), ZoneId.systemDefault()));
        } else {
            favorite.setCreatedAt(LocalDateTime.now());
        }
        try {
            favoriteMapper.insert(favorite);
            log.info("[FavoriteUnlike] FAVORITE落库: userId={}, noteId={}",
                    event.getUserId(), event.getNoteId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引冲突 = 重复消费，幂等忽略
            log.debug("[FavoriteUnlike] FAVORITE重复(幂等): userId={}, noteId={}",
                    event.getUserId(), event.getNoteId());
        }
    }

    private void handleUnfavorite(FavoriteEvent event) {
        // DELETE 天然幂等
        int deleted = favoriteMapper.deleteByUserAndNote(event.getUserId(), event.getNoteId());
        log.info("[FavoriteUnlike] UNFAVORITE删除: userId={}, noteId={}, deleted={}",
                event.getUserId(), event.getNoteId(), deleted);
    }
}
