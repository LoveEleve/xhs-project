package com.myxhs.coupon.job;

import com.myxhs.coupon.mapper.UserCouponMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 优惠券过期定时任务（XXL-Job 分布式调度）
 * <p>
 * 每小时扫描已过期但状态仍为"未使用"的券，分批更新为"已过期"。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 * <p>
 * 分批处理：
 * 每次 UPDATE LIMIT 1000，循环执行直到没有更多过期券。
 * 避免一次性 UPDATE 百万行导致长时间锁表。
 * </p>
 * <p>
 * 注意：用券时有实时有效期校验（ExpireValidator），不依赖此任务。
 * 此任务的作用是：
 * 1. 保持数据状态准确（用户查看"我的优惠券"时显示正确状态）
 * 2. 减少可用券列表的查询量（过期券不会出现在可用列表中）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireJob {

    private final UserCouponMapper userCouponMapper;

    private static final int BATCH_SIZE = 1000;

    /**
     * 优惠券过期扫描（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 * * * ?（每小时）
     */
    @XxlJob("couponExpireJob")
    public void expireCoupons() {
        try {
            int totalAffected = doExpire();
            XxlJobHelper.handleSuccess("标记 " + totalAffected + " 张券过期");
        } catch (Exception e) {
            log.error("[券过期] 执行异常", e);
            XxlJobHelper.handleFail("券过期处理异常: " + e.getMessage());
        }
    }

    /**
     * 分批过期处理
     * <p>
     * 循环执行 UPDATE LIMIT 1000，直到没有更多过期券。
     * 每批之间 sleep 100ms，给其他事务让出锁资源。
     * </p>
     *
     * @return 标记过期的券总数
     */
    private int doExpire() {
        log.info("[券过期] 开始分批扫描...");
        long startTime = System.currentTimeMillis();
        int totalAffected = 0;
        int batchCount = 0;

        try {
            while (true) {
                int affected = userCouponMapper.batchExpire(BATCH_SIZE);
                totalAffected += affected;
                batchCount++;

                if (affected < BATCH_SIZE) {
                    break;
                }

                Thread.sleep(100);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[券过期] 完成: 共{}批, 标记{}张券过期, 耗时{}ms",
                    batchCount, totalAffected, elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[券过期] 分批处理被中断, 已处理{}张", totalAffected);
        }

        return totalAffected;
    }
}
