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
import java.util.List;

/**
 * 订单号映射表补录定时任务（XXL-Job 分布式调度）
 * <p>
 * 场景：下单时映射表写入失败（公共库不可用、网络抖动等），
 * 导致通过订单号查询无法路由到分片库。
 * </p>
 * <p>
 * 策略：每 5 分钟按 ID 游标扫描所有订单，
 * 检查映射表中是否存在对应记录，不存在则补录，彻底消除时间窗口导致的永久遗漏。
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
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /** 滚动扫描游标（Redis）：每轮从上次位置继续，扫到尾回绕——原实现每轮从 0 全表扫 */
    private static final String CURSOR_KEY = "myxhs:order:mapping:repair:cursor";
    /** 每轮最多扫描页数（200 行/页）：把"全表扫"改成有界滚动，其余下轮继续 */
    private static final int MAX_BATCHES_PER_RUN = 10;

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
        long lastId = readCursor();
        int repaired = 0;
        int batches = 0;

        // 有界滚动扫描：每轮最多 MAX_BATCHES_PER_RUN 页（原实现 while(true) 直到扫完全表，
        // 且逐行 selectByOrderNo —— 订单量上来后每 5 分钟一次全表 + N+1 查询，生产不可接受）
        while (batches++ < MAX_BATCHES_PER_RUN) {
            List<Order> orders = orderMapper.selectOrdersForMappingRepair(lastId, BATCH_SIZE);
            if (orders.isEmpty()) {
                lastId = 0L;   // 扫到尾 → 下轮回绕重扫（持续兜底历史缺口）
                break;
            }

            // 批量查已有映射（一次 IN 替代 N 次单查）
            java.util.Set<Long> existingIds = orderNoMappingRepository.selectExistingOrderIds(
                    orders.stream().map(Order::getId).collect(java.util.stream.Collectors.toList()));

            for (Order order : orders) {
                if (existingIds.contains(order.getId())) {
                    continue;
                }
                try {
                    OrderNoMapping mapping = new OrderNoMapping();
                    mapping.setOrderNo(order.getOrderNo());
                    mapping.setUserId(order.getUserId());
                    mapping.setOrderId(order.getId());
                    orderNoMappingRepository.insert(mapping);
                    repaired++;
                    log.info("[映射补录] 补录成功: orderNo={}, userId={}, orderId={}",
                            order.getOrderNo(), order.getUserId(), order.getId());
                } catch (org.springframework.dao.DuplicateKeyException dup) {
                    log.debug("[映射补录] 并发已补录(唯一键): orderNo={}", order.getOrderNo());
                } catch (Exception e) {
                    log.warn("[映射补录] 补录失败: orderNo={}, orderId={}", order.getOrderNo(), order.getId(), e);
                }
            }

            lastId = orders.get(orders.size() - 1).getId();
            if (orders.size() < BATCH_SIZE) {
                lastId = 0L;   // 最后一页 → 回绕
                break;
            }
        }

        writeCursor(lastId);
        if (repaired > 0) {
            log.info("[映射补录] 完成: 补录{}条, 游标={}", repaired, lastId);
        }
        return repaired;
    }

    private long readCursor() {
        try {
            String v = stringRedisTemplate.opsForValue().get(CURSOR_KEY);
            return v != null ? Long.parseLong(v) : 0L;
        } catch (Exception e) {
            log.warn("[映射补录] 读取游标失败, 从 0 开始", e);
            return 0L;
        }
    }

    private void writeCursor(long cursor) {
        try {
            stringRedisTemplate.opsForValue().set(CURSOR_KEY, String.valueOf(cursor));
        } catch (Exception e) {
            log.warn("[映射补录] 写游标失败(下轮从头扫)", e);
        }
    }
}
