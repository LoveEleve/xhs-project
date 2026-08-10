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
 * 每 5 分钟扫描活跃用户，比较 Redis 未读计数与 MySQL COUNT，
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

    /**
     * 未读计数对账（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0/5 * * * ?（每 5 分钟）
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

            Map<Long, Map<Integer, Integer>> userTypeCountMap = new HashMap<>();
            for (Notification n : batch) {
                userTypeCountMap
                        .computeIfAbsent(n.getUserId(), k -> new HashMap<>())
                        .merge(n.getType(), 1, Integer::sum);
            }

            for (Map.Entry<Long, Map<Integer, Integer>> entry : userTypeCountMap.entrySet()) {
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
            }

            // 更新游标：取本批次最后一条通知的 id
            if (!batch.isEmpty()) {
                lastId = batch.get(batch.size() - 1).getId();
            }
            totalChecked += userTypeCountMap.size();

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

        if (totalFixed > 0) {
            log.info("[对账] 完成: 检查{}个用户, 修复{}个", totalChecked, totalFixed);
        }

        return new int[]{totalChecked, totalFixed};
    }
}
