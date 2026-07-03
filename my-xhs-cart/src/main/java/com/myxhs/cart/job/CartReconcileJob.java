package com.myxhs.cart.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.cart.entity.CartItem;
import com.myxhs.cart.mapper.CartItemMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 购物车对账修复任务（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 4 点，Redis ↔ MySQL 对比，以 Redis 为准修复。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 * <p>
 * 修复策略（Redis 为权威数据源）：
 * 1. Redis 有 + MySQL 无 → INSERT MySQL（MQ 消息丢失导致）
 * 2. Redis 有 + MySQL 有 + 数量不一致 → UPDATE MySQL（以 Redis 为准）
 * 3. Redis 无 + MySQL 有 → DELETE MySQL（用户已删除但 MQ 消息丢失）
 * </p>
 * <p>
 * 为什么以 Redis 为准？
 * - Redis 是权威数据源，所有用户操作直接写 Redis
 * - MySQL 只是异步持久化的兜底存储
 * - 如果 Redis 数据丢失（主从切换/重启），则以 MySQL 为准恢复 Redis
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartReconcileJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final CartItemMapper cartItemMapper;
    private final IdGeneratorUtil idGeneratorUtil;

    private static final String KEY_PREFIX = "myxhs:cart:{";
    private static final String ITEMS_KEY_SUFFIX = "}:items";
    private static final String CHECKED_KEY_SUFFIX = "}:checked";

    private static String itemsKey(Long userId) { return KEY_PREFIX + userId + ITEMS_KEY_SUFFIX; }
    private static String checkedKey(Long userId) { return KEY_PREFIX + userId + CHECKED_KEY_SUFFIX; }

    /**
     * 购物车对账修复（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 4 * * ?（每天凌晨 4 点）
     */
    @XxlJob("cartReconcileJob")
    public void reconcile() {
        log.info("[购物车对账] 开始执行...");
        long startTime = System.currentTimeMillis();
        int repairCount = 0;

        try {
            // 1. 获取 MySQL 中所有有购物车记录的用户 ID（去重）
            List<Long> userIds = cartItemMapper.selectDistinctUserIds();
            log.info("[购物车对账] 待对账用户数: {}", userIds.size());

            for (Long userId : userIds) {
                repairCount += reconcileUser(userId);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[购物车对账] 完成: 对账{}个用户, 修复{}条记录, 耗时{}ms",
                    userIds.size(), repairCount, elapsed);
            XxlJobHelper.handleSuccess("对账完成，修复 " + repairCount + " 条记录");
        } catch (Exception e) {
            log.error("[购物车对账] 执行异常", e);
            XxlJobHelper.handleFail("购物车对账异常: " + e.getMessage());
        }
    }

    /**
     * 对账单个用户的购物车
     *
     * @return 修复的记录数
     */
    private int reconcileUser(Long userId) {
        String itemsKey = itemsKey(userId);
        int repairCount = 0;

        // 获取 Redis 中的购物车数据
        Map<Object, Object> redisItems = stringRedisTemplate.opsForHash().entries(itemsKey);
        Set<String> redisChecked = stringRedisTemplate.opsForSet().members(checkedKey(userId));

        // 获取 MySQL 中的购物车数据
        List<CartItem> mysqlItems = cartItemMapper.selectList(
                new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));

        // 场景 1：Redis 有 + MySQL 无 → INSERT MySQL（MQ 消息丢失导致）
        for (Map.Entry<Object, Object> entry : redisItems.entrySet()) {
            String skuIdStr = entry.getKey().toString();
            Long skuId = Long.parseLong(skuIdStr);
            int redisQty = Integer.parseInt(entry.getValue().toString());

            boolean existsInMysql = mysqlItems.stream()
                    .anyMatch(item -> item.getSkuId().equals(skuId));

            if (!existsInMysql) {
                try {
                    boolean isChecked = redisChecked != null && redisChecked.contains(skuIdStr);
                    CartItem item = new CartItem();
                    item.setId(idGeneratorUtil.nextId());
                    item.setUserId(userId);
                    item.setSkuId(skuId);
                    item.setQuantity(redisQty);
                    item.setChecked(isChecked ? 1 : 0);
                    cartItemMapper.insert(item);
                    repairCount++;
                    log.info("[购物车对账] 补录MySQL: userId={}, skuId={}, qty={}",
                            userId, skuId, redisQty);
                } catch (Exception e) {
                    log.warn("[购物车对账] 补录失败(可能已存在): userId={}, skuId={}",
                            userId, skuId);
                }
            }
        }

        // 场景 2：Redis 有 + MySQL 有 + 数量/选中状态不一致 → UPDATE MySQL
        for (CartItem mysqlItem : mysqlItems) {
            Object redisQtyObj = redisItems.get(String.valueOf(mysqlItem.getSkuId()));
            if (redisQtyObj != null) {
                int redisQty = Integer.parseInt(redisQtyObj.toString());
                boolean isChecked = redisChecked != null
                        && redisChecked.contains(String.valueOf(mysqlItem.getSkuId()));
                int redisCheckedVal = isChecked ? 1 : 0;

                if (redisQty != mysqlItem.getQuantity() || redisCheckedVal != mysqlItem.getChecked()) {
                    int oldQty = mysqlItem.getQuantity();
                    int oldChecked = mysqlItem.getChecked();
                    mysqlItem.setQuantity(redisQty);
                    mysqlItem.setChecked(redisCheckedVal);
                    cartItemMapper.updateById(mysqlItem);
                    repairCount++;
                    log.info("[购物车对账] 修复: userId={}, skuId={}, qty: {}→{}, checked: {}→{}",
                            userId, mysqlItem.getSkuId(), oldQty, redisQty, oldChecked, redisCheckedVal);
                }
            }
        }

        // 场景 3：Redis 无 + MySQL 有 → DELETE MySQL（用户已删除）
        for (CartItem mysqlItem : mysqlItems) {
            boolean existsInRedis = redisItems.containsKey(String.valueOf(mysqlItem.getSkuId()));
            if (!existsInRedis) {
                cartItemMapper.deleteById(mysqlItem.getId());
                repairCount++;
                log.info("[购物车对账] 删除MySQL残留: userId={}, skuId={}",
                        userId, mysqlItem.getSkuId());
            }
        }

        return repairCount;
    }
}
