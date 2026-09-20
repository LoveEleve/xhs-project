package com.myxhs.counter.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.counter.service.CounterService;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 计数事件 MQ 消费者 — 消费 SOCIAL_TOPIC 更新计数
 * <p>
 * 消费 SOCIAL_TOPIC 的 LIKE/UNLIKE/FAVORITE/UNFAVORITE/COMMENT/UNCOMMENT Tag，
 * 根据事件类型映射到对应的计数维度，执行 Redis 原子去重 + INCR/DECR + Buffer 写入。
 * <p>
 * 消息来源：
 * - analytics 服务：点赞/取消点赞/收藏/取消收藏事件 → 计数更新
 * - content 服务：评论/删除评论事件 → 评论计数更新【修复R1】
 * <p>
 * 事件到计数的映射规则：
 * - LIKE(bizType=1)→ 笔记点赞数 +1（targetType=1, countType=1）
 * - LIKE(bizType=2)→ 评论点赞数 +1（targetType=3, countType=1）【修复H4】
 * - UNLIKE    → 对应 -1
 * - FAVORITE  → 笔记收藏数 +1（targetType=1, countType=2）
 * - UNFAVORITE→ 笔记收藏数 -1
 * - COMMENT   → 笔记评论数 +1（targetType=1, countType=3）【修复R1】
 * - UNCOMMENT → 笔记评论数 -1（targetType=1, countType=3）【修复R1】
 * <p>
 * 幂等性（生产级修复 m3）：
 * - 使用 Lua 脚本原子化「msgId 去重 + 计数增减」，消除竞态窗口
 * - 重复消息被去重拦截（status=0），Consumer 静默跳过并 ACK
 * - 归零保护消息正常 ACK（status=-1），避免无限重试
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "LIKE||UNLIKE||FAVORITE||UNFAVORITE||COMMENT||UNCOMMENT||SHARE||VIEW||FOLLOW||UNFOLLOW",
        consumerGroup = "counter-consumer-group",
        maxReconsumeTimes = 3
)
public class CounterEventConsumer implements RocketMQListener<MessageExt> {

    private final CounterService counterService;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /** 点赞事件版本门（2026-09-20 review）：防"后到的旧事件"改写计数（与 analytics 侧同构语义） */
    private static final String LIKE_VERSION_PREFIX = "myxhs:counter:event:version:like:";
    private static final long LIKE_VERSION_TTL_HOURS = 24;

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> LIKE_VERSION_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local cur = redis.call('GET', KEYS[1]) " +
                    "if cur and tonumber(cur) >= tonumber(ARGV[1]) then return 0 end " +
                    "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
                    "return 1",
                    Long.class);

    private boolean isStaleLikeEvent(Long userId, Integer bizType, Long bizId, Long actionTime) {
        String key = LIKE_VERSION_PREFIX + userId + ":" + bizType + ":" + bizId;
        Long passed = stringRedisTemplate.execute(LIKE_VERSION_SCRIPT,
                java.util.List.of(key), String.valueOf(actionTime),
                String.valueOf(LIKE_VERSION_TTL_HOURS * 3600));
        return passed == null || passed == 0;
    }

    /** 收藏事件版本门（2026-09-20 review：与点赞同构，防"后到的旧 UNFAVORITE"改写收藏数） */
    private static final String FAVORITE_VERSION_PREFIX = "myxhs:counter:event:version:favorite:";

    private boolean isStaleFavoriteEvent(Long userId, Long noteId, Long actionTime) {
        String key = FAVORITE_VERSION_PREFIX + userId + ":" + noteId;
        Long passed = stringRedisTemplate.execute(LIKE_VERSION_SCRIPT,
                java.util.List.of(key), String.valueOf(actionTime),
                String.valueOf(LIKE_VERSION_TTL_HOURS * 3600));
        return passed == null || passed == 0;
    }

    /** 计数类型常量：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注 */
    private static final int COUNT_TYPE_LIKE = 1;
    private static final int COUNT_TYPE_FAVORITE = 2;
    private static final int COUNT_TYPE_COMMENT = 3;
    private static final int COUNT_TYPE_SHARE = 4;
    private static final int COUNT_TYPE_VIEW = 5;
    private static final int COUNT_TYPE_FOLLOWER = 6;
    private static final int COUNT_TYPE_FOLLOWING = 7;

    /** 目标类型常量：1-笔记 2-用户 3-评论 4-商品 */
    private static final int TARGET_TYPE_NOTE = 1;
    private static final int TARGET_TYPE_USER = 2;
    private static final int TARGET_TYPE_COMMENT = 3;
    private static final int TARGET_TYPE_PRODUCT = 4;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceContext(msg);
        String msgId = msg.getMsgId();
        try {
            String tag = msg.getTags();
            if (tag == null) {
                log.warn("[计数Consumer] 消息无 Tag, 跳过: msgId={}", msgId);
                return;
            }
            String message = new String(msg.getBody(), StandardCharsets.UTF_8);

            log.debug("[计数Consumer] 收到消息: tag={}, msgId={}", tag, msgId);

            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = objectMapper.readValue(message, Map.class);

            switch (tag) {
                case "LIKE":
                case "UNLIKE":
                    handleLikeEvent(msgId, eventMap, tag);
                    break;
                case "FAVORITE":
                case "UNFAVORITE":
                    handleFavoriteEvent(msgId, eventMap, tag);
                    break;
                case "COMMENT":
                case "UNCOMMENT":
                    handleCommentEvent(msgId, eventMap, tag);
                    break;
                case "SHARE":
                    handleShareEvent(msgId, eventMap);
                    break;
                case "VIEW":
                    handleViewEvent(msgId, eventMap);
                    break;
                case "FOLLOW":
                case "UNFOLLOW":
                    handleFollowEvent(msgId, eventMap, tag);
                    break;
                default:
                    log.warn("[计数Consumer] 未知Tag: {}, 忽略消息", tag);
            }
        } catch (Exception e) {
            log.error("[计数Consumer] 消费失败: msgId={}", msgId, e);
            throw new RuntimeException("计数消息消费失败，触发重试", e);
        } finally {
            MqTraceHelper.clearTraceContext();
        }
    }

    /**
     * 处理点赞/取消点赞事件
     * <p>
     * LikeEvent 字段：userId, bizType(1-笔记 2-评论), bizId, action(LIKE/UNLIKE), actionTime
     * 当 bizType=1(笔记) 时，更新笔记的点赞计数。
     * 当 bizType=2(评论) 时，更新评论的点赞计数。【修复H4】
     * <p>
     * 【修复H2】使用 Set-based 计数替代 delta 计数：
     * - SADD/SREM Redis Set，计数 = SCARD，天然消除 MQ 乱序问题
     * - 先到的 UNLIKE 是 no-op（SREM 空 Set），后到的 LIKE 正常 SADD → SCARD=1，最终状态正确
     * </p>
     */
    private void handleLikeEvent(String msgId, Map<String, Object> eventMap, String tag) {
        Integer bizType = toInt(eventMap.get("bizType"));
        Long bizId = toLong(eventMap.get("bizId"));
        Long userId = toLong(eventMap.get("userId"));

        if (bizType == null || bizId == null) {
            log.warn("[计数Consumer] 点赞事件缺少必填字段: bizType={}, bizId={}", bizType, bizId);
            return;
        }
        if (userId == null) {
            log.warn("[计数Consumer] 点赞事件缺少 userId，无法使用 Set-based 计数: bizType={}, bizId={}", bizType, bizId);
            return;
        }

        // 版本门：actionTime 更旧的事件一律跳过（原实现只靠 SADD/SREM 可交换性，
        // 实测"后到的旧 UNLIKE"会把计数改回 0，与 analytics 侧（版本校验后拒绝）产生分叉）
        Long actionTime = toLong(eventMap.get("actionTime"));
        if (actionTime != null && isStaleLikeEvent(userId, bizType, bizId, actionTime)) {
            log.info("[计数Consumer] 跳过旧点赞事件(版本门): userId={}, bizType={}, bizId={}, actionTime={}",
                    userId, bizType, bizId, actionTime);
            return;
        }

        int targetType;
        if (bizType == 1) {
            targetType = TARGET_TYPE_NOTE;
        } else if (bizType == 2) {
            targetType = TARGET_TYPE_COMMENT;
        } else {
            log.warn("[计数Consumer] 未知 bizType={}, 忽略", bizType);
            return;
        }

        boolean isLike = "LIKE".equals(tag);
        boolean executed = counterService.setBasedLikeWithDedup(
                msgId, targetType, bizId, userId, isLike);

        log.info("[计数Consumer] {}点赞计数{}: msgId={}, bizType={}, targetType={}, targetId={}, userId={}, action={}",
                bizType == 2 ? "评论" : "",
                executed ? "更新" : "去重跳过",
                msgId, bizType, targetType, bizId, userId, tag);
    }

    /**
     * 处理收藏/取消收藏事件
     * <p>
     * FavoriteEvent 字段：userId, noteId, action(FAVORITE/UNFAVORITE), timestamp
     * 收藏只针对笔记，targetType=1, countType=2(收藏)
     * 使用 incrementWithDedup/decrementWithDedup 保证 MQ 幂等。
     * </p>
     */
    private void handleFavoriteEvent(String msgId, Map<String, Object> eventMap, String tag) {
        Long noteId = toLong(eventMap.get("noteId"));

        if (noteId == null) {
            log.warn("[计数Consumer] 收藏事件缺少必填字段: noteId={}", noteId);
            return;
        }

        int targetType = TARGET_TYPE_NOTE;
        int countType = COUNT_TYPE_FAVORITE;

        // 版本门：actionTime 更旧的收藏/取消收藏事件跳过（与点赞同构）
        Long userId = toLong(eventMap.get("userId"));
        Long actionTime = toLong(eventMap.get("actionTime"));
        if (userId != null && actionTime != null && isStaleFavoriteEvent(userId, noteId, actionTime)) {
            log.info("[计数Consumer] 跳过旧收藏事件(版本门): userId={}, noteId={}, actionTime={}",
                    userId, noteId, actionTime);
            return;
        }

        boolean executed;
        if ("FAVORITE".equals(tag)) {
            executed = counterService.incrementWithDedup(msgId, targetType, noteId, countType);
        } else {
            executed = counterService.decrementWithDedup(msgId, targetType, noteId, countType);
        }

        log.info("[计数Consumer] 收藏计数{}: msgId={}, targetType={}, targetId={}, countType={}, action={}",
                executed ? "更新" : "去重跳过", msgId, targetType, noteId, countType, tag);
    }

    /**
     * 处理评论/删除评论事件
     * <p>
     * 评论事件字段：noteId（被评论的笔记ID）
     * 固定映射：targetType=1(NOTE), countType=3(COMMENT)
     * 注意：评论计数不区分具体的评论ID，每次发表/删除评论对笔记计数 ±1。
     * </p>
     */
    private void handleCommentEvent(String msgId, Map<String, Object> eventMap, String tag) {
        Long noteId = toLong(eventMap.get("noteId"));

        if (noteId == null) {
            log.warn("[计数Consumer] 评论事件缺少必填字段: noteId={}", noteId);
            return;
        }

        int targetType = TARGET_TYPE_NOTE;
        int countType = COUNT_TYPE_COMMENT;

        boolean executed;
        if ("COMMENT".equals(tag)) {
            executed = counterService.incrementWithDedup(msgId, targetType, noteId, countType);
        } else {
            // O-Counter-2 修复：UNCOMMENT 事件带 count（级联删除 1+N 条）——按 count 递减，非固定 1
            long delta = 1;
            Object countObj = eventMap.get("count");
            if (countObj != null) {
                try { delta = Long.parseLong(countObj.toString()); } catch (NumberFormatException ignored) {}
            }
            executed = counterService.decrementWithDedup(msgId, targetType, noteId, countType, delta);
        }

        log.info("[计数Consumer] 评论计数{}: msgId={}, targetType={}, targetId={}, countType=COMMENT, action={}",
                executed ? "更新" : "去重跳过", msgId, targetType, noteId, tag);
    }

    /**
     * 处理分享事件【修复R5】
     * <p>
     * 分享事件字段：noteId（被分享的笔记ID）
     * 固定映射：targetType=1(NOTE), countType=4(SHARE)
     * 分享只增不减，不做 UN-SHARE 消费。
     * </p>
     */
    private void handleShareEvent(String msgId, Map<String, Object> eventMap) {
        Long noteId = toLong(eventMap.get("noteId"));
        if (noteId == null) {
            log.warn("[计数Consumer] 分享事件缺少必填字段: noteId={}", noteId);
            return;
        }

        int targetType = TARGET_TYPE_NOTE;
        int countType = COUNT_TYPE_SHARE;
        boolean executed = counterService.incrementWithDedup(msgId, targetType, noteId, countType);

        log.info("[计数Consumer] 分享计数{}: msgId={}, targetType={}, targetId={}, countType=SHARE",
                executed ? "更新" : "去重跳过", msgId, targetType, noteId);
    }

    /**
     * 处理浏览事件【修复R9】
     * <p>
     * 浏览事件字段：noteId（被浏览的笔记ID）
     * 固定映射：targetType=1(NOTE), countType=5(VIEW)
     * 浏览只增不减。
     * </p>
     */
    private void handleViewEvent(String msgId, Map<String, Object> eventMap) {
        Long noteId = toLong(eventMap.get("noteId"));
        if (noteId == null) {
            log.warn("[计数Consumer] 浏览事件缺少必填字段: noteId={}", noteId);
            return;
        }

        int targetType = TARGET_TYPE_NOTE;
        int countType = COUNT_TYPE_VIEW;
        boolean executed = counterService.incrementWithDedup(msgId, targetType, noteId, countType);

        log.debug("[计数Consumer] 浏览计数{}: msgId={}, targetType={}, targetId={}, countType=VIEW",
                executed ? "更新" : "去重跳过", msgId, targetType, noteId);
    }

    /**
     * 处理关注/取关事件【修复R10】
     * <p>
     * 关注事件字段：followerUserId, followeeUserId, action(FOLLOW/UNFOLLOW)
     * 双向更新：
     *   - follower 的 FOLLOWING 计数 (targetType=2, countType=7)
     *   - followee 的 FOLLOWER 计数 (targetType=2, countType=6)
     * </p>
     */
    private void handleFollowEvent(String msgId, Map<String, Object> eventMap, String tag) {
        Long followerUserId = toLong(eventMap.get("followerUserId"));
        Long followeeUserId = toLong(eventMap.get("followeeUserId"));

        if (followerUserId == null || followeeUserId == null) {
            log.warn("[计数Consumer] 关注事件缺少必填字段: follower={}, followee={}",
                    followerUserId, followeeUserId);
            return;
        }

        boolean isFollow = "FOLLOW".equals(tag);

        // 1. follower 的关注数 (countType=7)
        boolean f1 = isFollow
                ? counterService.incrementWithDedup(msgId, TARGET_TYPE_USER, followerUserId, COUNT_TYPE_FOLLOWING)
                : counterService.decrementWithDedup(msgId, TARGET_TYPE_USER, followerUserId, COUNT_TYPE_FOLLOWING);

        // 2. followee 的粉丝数 (countType=6) — 使用 msgId+"_2" 避免 dedup 冲突
        String msgId2 = msgId + "_2";
        boolean f2 = isFollow
                ? counterService.incrementWithDedup(msgId2, TARGET_TYPE_USER, followeeUserId, COUNT_TYPE_FOLLOWER)
                : counterService.decrementWithDedup(msgId2, TARGET_TYPE_USER, followeeUserId, COUNT_TYPE_FOLLOWER);

        // 部分成功回滚：followee 更新失败时回滚 follower
        if (!f2 && f1) {
            String rollbackMsgId = msgId + "_rollback";
            if (isFollow) {
                counterService.decrementWithDedup(rollbackMsgId, TARGET_TYPE_USER, followerUserId, COUNT_TYPE_FOLLOWING);
            } else {
                counterService.incrementWithDedup(rollbackMsgId, TARGET_TYPE_USER, followerUserId, COUNT_TYPE_FOLLOWING);
            }
            throw new RuntimeException("关注事件 followee 更新失败，已回滚 follower");
        }

        log.info("[计数Consumer] 关注计数更新: msgId={}, follower={}({}), followee={}({}), action={}",
                msgId, followerUserId, f1 ? "更新" : "去重", followeeUserId, f2 ? "更新" : "去重", tag);
    }

    private static Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
