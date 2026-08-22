package com.myxhs.order.job;

import com.myxhs.order.entity.Order;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.service.OrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时关单定时任务（兜底方案）（XXL-Job 分布式调度）
 * <p>
 * 每分钟扫描 30 分钟前创建且仍为"待付款"的订单，执行关单。
 * </p>
 * <p>
 * 为什么需要定时任务兜底？
 * RocketMQ 延时消息可能丢失（Broker 宕机、磁盘故障等极端情况）。
 * 定时任务作为最后的保障，确保超时订单一定会被关闭。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseJob {

    private static final String ORDER_COMPENSATION_FALLBACK_KEY = "myxhs:order:compensation:pending";

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private static final int BATCH_SIZE = 100;

    /**
     * 超时关单扫描（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0/1 * * * ?（每分钟）
     */
    @XxlJob("orderCloseJob")
    public void closeTimeoutOrders() {
        try {
            int totalClosed = doClose();
            XxlJobHelper.handleSuccess("兜底关单完成，关闭 " + totalClosed + " 条");
        } catch (Exception e) {
            log.error("[兜底关单] 执行异常", e);
            XxlJobHelper.handleFail("兜底关单异常: " + e.getMessage());
        }
    }

    private int doClose() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(30);
        long lastId = 0L;
        int totalClosed = 0;
        int totalScanned = 0;

        // 游标分页：每次取 BATCH_SIZE 条，直到没有更多数据
        while (true) {
            List<Order> timeoutOrders = orderMapper.selectTimeoutOrders(deadline, lastId, BATCH_SIZE);
            if (timeoutOrders.isEmpty()) {
                break;
            }

            totalScanned += timeoutOrders.size();
            for (Order order : timeoutOrders) {
                try {
                    orderService.closeTimeoutOrder(order.getId(), order.getUserId());
                    totalClosed++;
                } catch (Exception e) {
                    log.error("[兜底关单] 失败: orderId={}", order.getId(), e);
                }
            }

            // 更新游标为本批次最后一条记录的 ID
            lastId = timeoutOrders.get(timeoutOrders.size() - 1).getId();

            // 如果本批次不足 BATCH_SIZE，说明已经没有更多数据
            if (timeoutOrders.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalScanned > 0) {
            log.info("[兜底关单] 完成: 扫描{}条, 关闭{}条", totalScanned, totalClosed);
        }

        replayCompensationFallback();
        return totalClosed;
    }

    private void replayCompensationFallback() {
        try {
            java.util.Set<String> pending = stringRedisTemplate.opsForSet().members(ORDER_COMPENSATION_FALLBACK_KEY);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            for (String member : pending) {
                String[] parts = member.split(":", 4);
                if (parts.length < 3) {
                    stringRedisTemplate.opsForSet().remove(ORDER_COMPENSATION_FALLBACK_KEY, member);
                    continue;
                }
                try {
                    String action = parts[0];
                    Long orderId = Long.valueOf(parts[1]);
                    Long userId = Long.valueOf(parts[2]);
                    switch (action) {
                        case "RELEASE_STOCK" -> orderService.compensateReleaseStock(orderId, userId);
                        case "RETURN_COUPON" -> orderService.compensateReturnCoupon(orderId, userId);
                        default -> orderService.closeTimeoutOrder(orderId, userId);
                    }
                    stringRedisTemplate.opsForSet().remove(ORDER_COMPENSATION_FALLBACK_KEY, member);
                    log.info("[兜底关单] 本地补偿兜底重放成功: {}", member);
                } catch (Exception e) {
                    log.warn("[兜底关单] 本地补偿兜底重放失败: {}", member, e);
                }
            }
        } catch (Exception e) {
            log.error("[兜底关单] 本地补偿兜底扫描异常", e);
        }
    }
}
