package com.myxhs.order.job;

import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderNoMapping;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.repository.OrderNoMappingRepository;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单号映射表补录定时任务（XXL-Job 分布式调度）
 * <p>
 * 场景：下单时映射表写入失败（公共库不可用、网络抖动等），
 * 导致通过订单号查询无法路由到分片库。
 * </p>
 * <p>
 * 策略：每 5 分钟扫描最近 1 小时内创建的订单，
 * 检查映射表中是否存在对应记录，不存在则补录。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMappingRepairJob {

    private final OrderMapper orderMapper;
    private final OrderNoMappingRepository orderNoMappingRepository;

    private static final int BATCH_SIZE = 200;

    /**
     * 订单号映射补录（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0/5 * * * ?（每 5 分钟）
     */
    @XxlJob("orderMappingRepairJob")
    public void repairMappings() {
        try {
            int repaired = doRepair();
            XxlJobHelper.handleSuccess("映射补录完成，补录 " + repaired + " 条");
        } catch (Exception e) {
            log.error("[映射补录] 执行异常", e);
            XxlJobHelper.handleFail("映射补录异常: " + e.getMessage());
        }
    }

    private int doRepair() {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long lastId = 0L;
        int repaired = 0;

        while (true) {
            List<Order> orders = orderMapper.selectRecentOrders(since, lastId, BATCH_SIZE);
            if (orders.isEmpty()) {
                break;
            }

            for (Order order : orders) {
                try {
                    OrderNoMapping existing = orderNoMappingRepository.selectByOrderNo(order.getOrderNo());
                    if (existing == null) {
                        OrderNoMapping mapping = new OrderNoMapping();
                        mapping.setOrderNo(order.getOrderNo());
                        mapping.setUserId(order.getUserId());
                        mapping.setOrderId(order.getId());
                        orderNoMappingRepository.insert(mapping);
                        repaired++;
                        log.info("[映射补录] 补录成功: orderNo={}, userId={}", order.getOrderNo(), order.getUserId());
                    }
                } catch (Exception e) {
                    log.debug("[映射补录] 补录异常(可能已存在): orderNo={}", order.getOrderNo());
                }
            }

            lastId = orders.get(orders.size() - 1).getId();
            if (orders.size() < BATCH_SIZE) {
                break;
            }
        }

        if (repaired > 0) {
            log.info("[映射补录] 完成: 补录{}条", repaired);
        }

        return repaired;
    }
}
