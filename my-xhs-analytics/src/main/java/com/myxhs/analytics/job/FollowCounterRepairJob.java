package com.myxhs.analytics.job;

import com.myxhs.analytics.mapper.FollowMapper;
import com.myxhs.analytics.service.FollowService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 关注/粉丝计数对账修复定时任务（XXL-Job 分布式调度）
 * <p>
 * 扫描 t_follow 表中所有有关注关系的用户，以 Redis ZSet 的 ZCARD 为准修复 counter 计数值。
 * Lua 脚本保证的是执行隔离性，而非事务回滚。极端场景下可能出现计数与 ZSet 成员数不一致。
 * </p>
 * <p>
 * Admin 配置建议：Cron = 0 0 &#42;/1 * * ? (每小时执行一次)
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowCounterRepairJob {

    private final FollowMapper followMapper;
    private final FollowService followService;

    private static final int BATCH_SIZE = 100;

    /**
     * 关注/粉丝计数对账修复（XXL-Job Handler）
     */
    @XxlJob("followCounterRepairJob")
    public void repairFollowCounters() {
        try {
            int totalScanned = doRepair();
            XxlJobHelper.handleSuccess("关注计数对账完成，扫描 " + totalScanned + " 个用户");
        } catch (Exception e) {
            log.error("[关注计数对账] 执行异常", e);
            XxlJobHelper.handleFail("关注计数对账异常: " + e.getMessage());
        }
    }

    private int doRepair() {
        long lastId = 0L;
        int totalScanned = 0;

        while (true) {
            List<Long> userIds = followMapper.selectDistinctUserIds(lastId, BATCH_SIZE);
            if (userIds.isEmpty()) {
                break;
            }

            totalScanned += userIds.size();
            for (Long userId : userIds) {
                try {
                    followService.repairUserCounters(userId);
                } catch (Exception e) {
                    log.error("[关注计数对账] 修复失败: userId={}", userId, e);
                }
            }

            Long maxId = followMapper.selectMaxIdByLastId(lastId, BATCH_SIZE);
            if (maxId == null || maxId <= lastId) {
                break;
            }
            lastId = maxId;
        }

        if (totalScanned > 0) {
            log.info("[关注计数对账] 完成: 扫描{}个用户", totalScanned);
        }

        return totalScanned;
    }
}
