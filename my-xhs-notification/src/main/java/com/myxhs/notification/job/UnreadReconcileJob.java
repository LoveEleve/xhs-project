package com.myxhs.notification.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.notification.entity.Notification;
import com.myxhs.notification.mapper.NotificationMapper;
import com.myxhs.notification.service.UnreadCountService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 未读计数对账定时任务（XXL-Job 分布式调度）
 * <p>
 * 每 10 分钟扫描活跃用户，比较 Redis 未读计数与 MySQL COUNT，
 * 发现差异时以 DB 为准修复 Redis。
 * </p>
 * <p>
 * 为什么需要对账？
 * 1. Redis 主从切换可能丢失 INCR/DECR 操作
 * 2. 并发标记已读时 DECR 可能漏执行（极端情况）
 * 3. 系统异常导致 INCR 成功但 DB 写入失败
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnreadReconcileJob {

    private final NotificationMapper notificationMapper;
    private final UnreadCountService unreadCountService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /**
     * 未读计数对账（XXL-Job Handler）
     * <p>
     * Admin 配置：每 10 分钟（cron 表达式见 init-xxljob.sql，此处不写完整表达式以免提前结束注释）
     */
    @XxlJob("unreadReconcileJob")
    public void reconcile() {
        try {
            int[] result = doReconcile();
            XxlJobHelper.handleSuccess("对账完成，检查 " + result[0] + " 个用户，修复 " + result[1] + " 个");
        } catch (Exception e) {
            log.error("[对账] 执行异常", e);
            XxlJobHelper.handleFail("未读对账异常: " + e.getMessage());
        }
    }

    /**
     * @return [totalChecked, totalFixed]
     */
    private int[] doReconcile() {
        int batchSize = 500;
        long lastId = 0;
        int totalFixed = 0;
        int totalChecked = 0;
        int queryLimit = batchSize * 10; // SQL LIMIT，按通知条数而非用户数

        // 全局累计（修复：原实现"每批各算各的"——用户未读跨批时，后一批的**部分计数**会覆盖
        // 前一批的完整计数，对账自身制造错值）。扫描全部完成后统一比对修复。
        Map<Long, Map<Integer, Integer>> allUserTypeCounts = new HashMap<>();

        while (true) {
            final long cursor = lastId;
            // 【修复: 坑51】改用 id 作为游标而非 userId——userId 游标会在 LIMIT 切割边界 userId 时
            // 跳过该 userId 的剩余记录，导致对账数据不完整
            List<Notification> batch = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getIsRead, 0)
                            .gt(Notification::getId, cursor)
                            .select(Notification::getId, Notification::getUserId, Notification::getType)
                            .orderByAsc(Notification::getId)
                            .last("LIMIT " + queryLimit));

            if (batch.isEmpty()) {
                break;
            }

            for (Notification n : batch) {
                allUserTypeCounts
                        .computeIfAbsent(n.getUserId(), k -> new HashMap<>())
                        .merge(n.getType(), 1, Integer::sum);
            }

            // 更新游标：取本批次最后一条通知的 id
            if (!batch.isEmpty()) {
                lastId = batch.get(batch.size() - 1).getId();
            }

            // 修复退出条件：检查通知条数而非用户数
            // 原逻辑 userCount < batchSize 会提前退出（500个用户可能有5000条通知）
            // 正确逻辑：通知条数 < LIMIT，说明已经没有更多数据了
            if (batch.size() < queryLimit) {
                break;
            }

            // 限速：每批次之间休眠 50ms，避免瞬间打满 Redis 带宽
            // 场景：Redis 重启后全部 Key 丢失，修复量很大时（比如 10 万用户），
            // 不限速会在几秒内执行 10 万次 SET 命令，瞬间打满 Redis
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[对账] 被中断，提前退出");
                break;
            }
        }

        // 扫描完成 → 每用户一份完整计数，逐户比对修复
        for (Map.Entry<Long, Map<Integer, Integer>> entry : allUserTypeCounts.entrySet()) {
            Long userId = entry.getKey();
            Map<Integer, Integer> typeCountMap = entry.getValue();
            int dbTotal = typeCountMap.values().stream().mapToInt(Integer::intValue).sum();

            var redisCount = unreadCountService.getUnreadCount(userId);
            if (redisCount.getTotal() != dbTotal) {
                unreadCountService.forceSetUnread(userId, dbTotal, typeCountMap);
                totalFixed++;
                log.info("[对账] 修复: userId={}, redis={}, db={}",
                        userId, redisCount.getTotal(), dbTotal);
            }
            totalChecked++;
        }

        // 覆盖缺口：用户"有 Redis 残留计数、但 DB 无未读行"时不在 allUserTypeCounts 里，永远修不到。
        // SCAN 未读键（含 hash tag 前缀），逐个核对 DB；为 0 则清零（限速：每 200 个 key 让 50ms）
        int staleFixed = reconcileStaleRedisOnly(allUserTypeCounts);
        totalFixed += staleFixed;

        if (totalFixed > 0) {
            log.info("[对账] 完成: 检查{}个用户, 修复{}个(其中残留清零{})", totalChecked, totalFixed, staleFixed);
        }

        purgeStaleNotificationsDaily();

        return new int[]{totalChecked, totalFixed};
    }

    /**
     * 通知表保留策略：每天一次（SETNX 日切标记），保留 180 天，有界排空
     */
    private void purgeStaleNotificationsDaily() {
        try {
            String marker = "myxhs:notification:cleanup:" + java.time.LocalDate.now();
            boolean first = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(marker, "1", java.time.Duration.ofHours(25)));
            if (!first) {
                return;
            }
            int total = 0;
            for (int i = 0; i < 40; i++) {
                int purged = notificationMapper.deleteStale(
                        java.time.LocalDateTime.now().minusDays(180), 5000);
                total += purged;
                if (purged < 5000) {
                    break;
                }
            }
            if (total > 0) {
                log.info("[通知保留] 清理 180 天前通知 {} 行", total);
            }
        } catch (Exception e) {
            log.error("[通知保留] 清理失败(明日重试；检查 idx_user_id_created)", e);
        }
    }

    /**
     * 清理"Redis 有计数但 DB 无未读"的残留键
     */
    private int reconcileStaleRedisOnly(Map<Long, Map<Integer, Integer>> allUserTypeCounts) {
        int fixed = 0;
        int scanned = 0;
        try (org.springframework.data.redis.core.Cursor<String> cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("myxhs:notification:unread:{u:*")
                        .count(200).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // myxhs:notification:unread:{u:123} 或 ...:type:{u:123}
                int start = key.indexOf("{u:");
                int end = key.lastIndexOf('}');
                if (start < 0 || end <= start + 3) {
                    continue;
                }
                Long userId;
                try {
                    userId = Long.parseLong(key.substring(start + 3, end));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (allUserTypeCounts.containsKey(userId)) {
                    continue; // 有未读行的用户已在上一步统一核对
                }
                Long dbUnread = notificationMapper.selectCount(
                        new LambdaQueryWrapper<Notification>()
                                .eq(Notification::getUserId, userId)
                                .eq(Notification::getIsRead, 0));
                if (dbUnread != null && dbUnread == 0) {
                    unreadCountService.forceSetUnread(userId, 0, java.util.Map.of());
                    fixed++;
                    log.info("[对账] 清理残留未读键: userId={}, key={}", userId, key);
                }
                if (++scanned % 200 == 0) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[对账] 残留未读键清理失败(下轮重试)", e);
        }
        return fixed;
    }
}
