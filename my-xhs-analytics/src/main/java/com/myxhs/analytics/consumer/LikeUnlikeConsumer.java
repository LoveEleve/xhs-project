package com.myxhs.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.analytics.dto.event.LikeEvent;
import com.myxhs.analytics.entity.Like;
import com.myxhs.analytics.mapper.LikeMapper;
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
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 点赞/取消点赞统一消费者 — 保证 LIKE/UNLIKE 同组消费顺序
 * <p>
 * 修复前：LikeConsumer(like-consumer-group) 与 UnlikeConsumer(unlike-consumer-group) 分属
 * 不同 consumer group——RocketMQ 不保证跨 group 消费顺序→UNLIKE先到LIKE后到→
 * 最终 DB 显示点赞但实际用户已取消。
 * <p>
 * 修复后：统一 consumer group + actionTime 版本号防乱序（Redis 记录最后处理版本，
 * 旧事件跳过）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "LIKE||UNLIKE",
        consumerGroup = "like-unlike-consumer-group",
        maxReconsumeTimes = 3
)
public class LikeUnlikeConsumer implements RocketMQListener<MessageExt> {

    private final LikeMapper likeMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;
    private final com.myxhs.common.mq.VersionGateHelper versionGate; // C-009 D3：完成态版本门（共用件）

    private static final String VERSION_PREFIX = "analytics:event:version:like:";
    private static final long VERSION_TTL_SECONDS = 24 * 3600;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        String versionKey = null;
        try {
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);
            LikeEvent event = objectMapper.readValue(message, LikeEvent.class);

            // C-009 D3 完成态版本门：只读检查旧事件（不 reserve——崩溃后重投可重处理；
            // 幂等前提：uk_user_biz + DuplicateKey 忽略 / DELETE 天然幂等）
            if (event.getActionTime() != null) {
                versionKey = VERSION_PREFIX + event.getUserId() + ":" + event.getBizType() + ":" + event.getBizId();
                if (versionGate.isStale(versionKey, event.getActionTime())) {
                    log.debug("[LikeUnlike] 跳过旧事件: userId={}, bizType={}, bizId={}, actionTime={}",
                            event.getUserId(), event.getBizType(), event.getBizId(), event.getActionTime());
                    return;
                }
            }

            if ("LIKE".equals(event.getAction())) {
                handleLike(event);
            } else {
                handleUnlike(event);
            }
            // 完成后单调推进（失败不推进 → 重投可重处理）
            if (versionKey != null) {
                versionGate.markDone(versionKey, event.getActionTime(), VERSION_TTL_SECONDS);
            }
        } catch (Exception e) {
            log.error("[LikeUnlike] 消费失败: {}", new String(msg.getBody()), e);
            // C-009：完成态语义——失败无需清理（未推进 → 重投可重处理）
            throw new RuntimeException("LikeUnlike消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private void handleLike(LikeEvent event) {
        Like like = new Like();
        like.setId(idGeneratorUtil.nextId());
        like.setUserId(event.getUserId());
        like.setBizType(event.getBizType());
        like.setBizId(event.getBizId());
        if (event.getActionTime() != null) {
            like.setCreatedAt(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(event.getActionTime()),
                    java.time.ZoneId.systemDefault()));
        } else {
            like.setCreatedAt(LocalDateTime.now());
        }
        try {
            likeMapper.insert(like);
            log.info("[LikeUnlike] LIKE落库: userId={}, bizType={}, bizId={}",
                    event.getUserId(), event.getBizType(), event.getBizId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("[LikeUnlike] LIKE重复(幂等): userId={}, bizType={}, bizId={}",
                    event.getUserId(), event.getBizType(), event.getBizId());
        }
    }

    private void handleUnlike(LikeEvent event) {
        int deleted = likeMapper.deleteByUserAndBiz(
                event.getUserId(), event.getBizType(), event.getBizId());
        log.info("[LikeUnlike] UNLIKE删除: userId={}, bizType={}, bizId={}, deleted={}",
                event.getUserId(), event.getBizType(), event.getBizId(), deleted);
    }
}
