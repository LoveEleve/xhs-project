package com.myxhs.notification.service;

import com.myxhs.notification.entity.Notification;
import com.myxhs.notification.entity.PushTemplate;
import com.myxhs.notification.mapper.NotificationMapper;
import com.myxhs.notification.mapper.PushTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.myxhs.notification.dto.NotificationType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知聚合器
 * <p>
 * 核心逻辑：5 分钟时间窗口内，同一类型 + 同一目标的通知合并为一条。
 * </p>
 * <p>
 * 实现方式：
 * 1. Redis SETNX 做窗口锁（Key = userId:type:targetId，TTL = 5 分钟）
 * 2. 窗口内第一条通知 → 直接 INSERT
 * 3. 窗口内后续通知 → 更新主通知的 aggregate_count 和 title
 * </p>
 * <p>
 * 采用"存储层聚合"策略：被聚合的通知不写 DB，只更新主通知。
 * 优点：DB 写入量减少 N 倍（N = 聚合倍率）。
 * 缺点：无法回溯每条被聚合通知的详情（可接受——通知场景用户只关心"谁+做了什么"）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationAggregator {

    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationMapper notificationMapper;
    private final PushTemplateMapper pushTemplateMapper;

    private static final String AGGREGATE_WINDOW_KEY = "notify:agg:";
    private static final Duration AGGREGATE_WINDOW = Duration.ofMinutes(5);

    /**
     * 模板本地缓存（避免每次聚合都查 DB）
     * <p>
     * 模板数据几乎不变，使用 ConcurrentHashMap 做本地缓存。
     * 如果需要动态更新模板，可以加 TTL 或监听配置变更。
     * </p>
     */
    private final ConcurrentHashMap<String, PushTemplate> templateCache = new ConcurrentHashMap<>();

    /**
     * Lua 脚本：原子性 SETNX + 写入通知 ID
     * <p>
     * 为什么用 Lua？
     * 原来的实现分两步：先 SETNX(key, "") 再 SET(key, notificationId)。
     * 两步之间如果进程崩溃，窗口锁存在但没有主通知 ID，后续聚合会走 fallback。
     * Lua 保证"检查 → 写入"是原子的。
     * </p>
     * <p>
     * 返回值：
     * - "1" = 窗口内第一条（SETNX 成功）
     * - 已有的 notificationId = 窗口内后续（SETNX 失败，返回已有值）
     * </p>
     */
    private static final String AGGREGATE_SETNX_SCRIPT =
            "local exists = redis.call('EXISTS', KEYS[1]) " +
            "if exists == 0 then " +
            "  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
            "  return '1' " +
            "else " +
            "  return redis.call('GET', KEYS[1]) " +
            "end";

    /**
     * 处理通知（含聚合逻辑）
     *
     * @param notification 待处理的通知（已填充基本字段）
     * @return 最终的通知记录（可能是新建的，也可能是更新后的主通知）
     */
    public Notification processWithAggregate(Notification notification) {
        // 聚合 Key = userId:type:targetId
        // 同一用户 + 同一类型 + 同一目标 → 5 分钟内合并
        String aggregateKey = AGGREGATE_WINDOW_KEY +
                notification.getUserId() + ":" +
                notification.getType() + ":" +
                notification.getTargetId();

        // 先插入 DB（无论是否聚合都需要一个 ID）
        notification.setAggregateCount(1);
        notification.setIsRead(0);
        notificationMapper.insert(notification);

        // Lua 原子操作：SETNX 窗口锁 + 写入通知 ID
        DefaultRedisScript<String> script = new DefaultRedisScript<>(AGGREGATE_SETNX_SCRIPT, String.class);
        String result = stringRedisTemplate.execute(script,
                Collections.singletonList(aggregateKey),
                String.valueOf(notification.getId()),
                String.valueOf(AGGREGATE_WINDOW.getSeconds()));

        if ("1".equals(result)) {
            // 窗口内第一条通知 → 已插入，直接返回
            log.debug("[聚合] 新建通知: userId={}, type={}, targetId={}, id={}",
                    notification.getUserId(), notification.getType(),
                    notification.getTargetId(), notification.getId());
            return notification;
        } else {
            // 窗口内后续通知 → 聚合到主通知
            // 当前通知已插入 DB，需要逻辑删除（避免重复展示）
            // 为什么用逻辑删除而不是物理删除？
            // 物理删除(deleteById)后如果进程崩溃，恢复逻辑(重新insert)可能因主键冲突失败。
            // 逻辑删除(set isDeleted=1)是幂等操作，不会出现主键冲突。
            // 同时查询通知列表时 MyBatis-Plus 的逻辑删除功能会自动过滤 isDeleted=1 的记录。
            notificationMapper.deleteById(notification.getId()); // MyBatis-Plus逻辑删除：UPDATE SET deleted=1

            if (result == null || result.isEmpty()) {
                // 极端情况：Lua 返回空，取消逻辑删除作为独立通知处理
                notification.setDeleted(0);
                notification.setAggregateCount(1);
                notification.setIsRead(0);
                notificationMapper.updateById(notification);
                return notification;
            }

            Long mainId = Long.parseLong(result);

            // 原子递增聚合计数 + 查询新值（修复 incrementAggregateCount 返回值误用）
            int affected = notificationMapper.incrementAggregateCount(mainId);
            if (affected <= 0) {
                // 主通知不存在（异常），取消逻辑删除恢复当前通知
                notification.setDeleted(0);
                notification.setAggregateCount(1);
                notification.setIsRead(0);
                notificationMapper.updateById(notification);
                return notification;
            }
            Integer newCount = notificationMapper.getAggregateCount(mainId);
            if (newCount == null) newCount = 1;

            // 更新聚合标题
            String aggregateTitle = buildAggregateTitle(
                    notification.getType(),
                    notification.getSenderName(),
                    newCount);
            notificationMapper.updateAggregateTitle(mainId, aggregateTitle);

            // 构建返回对象
            Notification mainNotification = notificationMapper.selectById(mainId);
            if (mainNotification == null) {
                mainNotification = notification;
            }

            log.debug("[聚合] 合并通知: mainId={}, newCount={}, sender={}",
                    mainId, newCount, notification.getSenderName());
            return mainNotification;
        }
    }

    /**
     * 构建聚合标题
     * <p>
     * 优先使用模板表中的聚合标题模板，否则使用默认格式。
     * </p>
     */
    private String buildAggregateTitle(Integer type, String senderName, int count) {
        NotificationType nt = NotificationType.fromCode(type);
        PushTemplate template = getTemplateWithCache(nt.getName());

        if (template != null && template.getAggregateTitleTemplate() != null) {
            return template.getAggregateTitleTemplate()
                    .replace("{sender}", senderName != null ? senderName : "某用户")
                    .replace("{count}", String.valueOf(count));
        }

        // 默认聚合格式
        return (senderName != null ? senderName : "某用户") + "等" + count + "人" + nt.getDefaultAction();
    }

    /**
     * 带本地缓存的模板查询
     */
    private PushTemplate getTemplateWithCache(String typeStr) {
        return templateCache.computeIfAbsent(typeStr, pushTemplateMapper::selectByType);
    }
}
