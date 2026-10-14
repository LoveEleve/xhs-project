# 10 · 兜底 Job 篇：四个 Job 全解（超时 / Outbox / 补偿 / 对账）

> 系列位置：04-08 讲完五大动作主链路（预扣/落账/确认/回退/回补）。本篇讲"**主链路失败之后，谁来收拾**"——四个兜底 Job。
> 核心源码：`PreDeductTimeoutJob` / `InventoryOutboxSenderJob` / `InventoryCompensationJob` / `InventoryReconcileJob`（代码逐字，imports 略）。
> 分桶口径（同 05 篇）：代码逐字含 `:bucket` 字段（绕不开）；但**分桶逻辑不深讲**（当前部署单桶、无收益），解读与例子一律按**去桶**理解——桶字段当透明。
> 建立：2026-10-11。

---

## 1. 全景：四个 Job 与兜底体系

### 1.1 它们是谁

| Job | 频率（代码） | 调度形态 | 一句话职责 | 漏跑/失败的后果 |
|---|---|---|---|---|
| `PreDeductTimeoutJob` | 1min | @Scheduled + 红锁 | 扫到期预扣（ZSet 索引）→ 释放 + RELEASE 事件 | 幽灵锁：货被占死 |
| `InventoryOutboxSenderJob` | 5s | @Scheduled + 红锁 | 扫 outbox `status=0` → 补发 MQ（退避 30→480s） | MySQL 账目滞后/事件丢失 |
| `InventoryCompensationJob` | 30s | @Scheduled + 红锁 | 补偿表重试（type=1 回滚 / type=2 回补）；重试 3 次即转人工 | 补偿堆积、库存泄漏 |
| `InventoryReconcileJob` | 1h（部署 SQL：`0 0 * * * ?`） | **XXL-Job** + 红锁 | Redis↔MySQL 对账 + locked 重算 + 分桶求和 + 表清理 | 账目漂移无人收敛 |

> 注：`job/` 包内还有第 5 个 Job `TccTimeoutJob`（TCC 事务超时回滚，`lock:job:inventory:tcc-timeout`、`TIMEOUT_MINUTES=10`、批 100）——TCC 链路未接线（09 ❌ 整篇排除），本文不含。

### 1.2 为什么叫"兜底"——失败点映射

```
主链路失败点                       对应兜底 Job
──────────────────────────────────────────────────────
消息发送失败 / 进程崩溃         →  OutboxSenderJob      （投递兜底）
预扣久未确认（超时）            →  PreDeductTimeoutJob  （占用兜底）
回滚 / 回补操作失败             →  CompensationJob      （重试兜底）
以上全部漏掉                    →  ReconcileJob         （最终一致兜底）
```

也就是 L1/L2/L3 三层体系里，**Job 群 = L2 的搬运工 + L3 的执行者**。

---

## 2. 调度形态评审（@Scheduled+红锁 vs XXL-Job）

### 2.1 现状

三个高频 Job 用"@Scheduled + Redisson 锁"（每个实例都调度，抢锁者执行）；对账用 XXL-Job（调度中心只派一个执行器）+ 红锁（防手动端点与调度重叠）。

### 2.2 两种形态的取舍

| | @Scheduled + 红锁 | XXL-Job |
|---|---|---|
| 单次运行保证 | 靠抢锁（N 选 1） | 调度中心单派发 |
| 优点 | 简单；不依赖调度中心（admin 挂了照跑）；适合秒级 | 执行历史/日志/手动触发/失败告警/分片；运维面完整 |
| 缺点 | N 实例空转调度；执行痕迹散在各实例日志；无中心化管理 | 多一个故障域（admin+DB）；秒级任务会让执行历史膨胀 |

### 2.3 评审结论（生产视角）

1. **秒级自愈中继（OutboxSender）→ 保持进程内**（XXL 不划算：每天约 1.7 万条执行历史、中心负载）；
2. **分钟级业务兜底（Timeout / Compensation）→ 建议迁 XXL**（要历史、告警、手动重跑）；
3. **人工批处理（Reconcile）→ XXL ✓**（已做）；
4. **共同铁律**：无论用哪种调度器，**保留分布式锁 + 任务幂等**——XXL 的"单次派发"≠"恰好一次"（手动触发/重试/路由切换仍可能重复或丢失）；
5. **四件运维面必须补齐**（评审改进清单）：① Job 心跳/未运行检测；② 失败告警；③ 堆积指标（outbox 待发数、补偿 pending 数、DLQ 大小）；④ 手动重跑入口。

### 2.4 注释漂移（读代码时注意，以代码/部署为准）

| 位置 | 注释写的 | 实际 |
|---|---|---|
| Reconcile 类 javadoc | "每天凌晨 3 点执行"、"无需 Redisson 分布式锁" | 部署 SQL cron = `0 0 * * * ?`（每小时）；代码有红锁（防手动端点重叠） |
| PreDeductTimeout javadoc | "每 5 分钟"、"leaseTime=240s" | 代码 `fixedRate=60_000`（1 分钟）、`tryLock(0, 40s)` |

---

## 3. Job ①：`PreDeductTimeoutJob`（超时释放）

### 3.1 全文

```java
/**
 * 预扣超时回退任务
 * <p>
 * 每 5 分钟扫描 Redis 中的预扣记录（inventory:prededuct:*），
 * 对 TTL 已过期或即将过期的记录执行库存回退。
 * </p>
 * <p>
 * 分布式安全保证：
 * 1. Redisson 分布式锁保证多实例部署时只有一个实例执行（防止重复扫描浪费资源）
 * 2. release.lua 脚本原子回退保证与用户主动释放不会双重回退
 * 3. Redis 回退成功后，RELEASE 事件同步发 MQ 并保留 Outbox，避免 MySQL locked_stock 永久掉队
 * </p>
 * <p>
 * 为什么需要主动扫描？
 * Redis Key 过期是惰性删除 + 定期删除，不保证精确过期。
 * 如果 Key 过期后没有被访问，可能长时间不被删除，导致库存被"幽灵锁定"。
 * 主动扫描 + 原子回退保证库存及时释放。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreDeductTimeoutJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> releaseScript;
    private final RedissonClient redissonClient;
    private final org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.myxhs.inventory.mapper.InventoryMapper inventoryMapper;

    private static final String PREDEDUCT_KEY_PREFIX = "inventory:prededuct:";
    private static final String PREDEDUCT_INDEX_KEY = "inventory:prededuct:index";
    private static final String TOTAL_KEY_TPL = "inventory:{%d}:total";
    private static final String BUCKET_KEY_TPL = "inventory:{%d}:bucket:";

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_TPL, skuId); }
    private static String bucketKey(Long skuId, int bucketNo) { return String.format(BUCKET_KEY_TPL, skuId) + bucketNo; }
    private static final String LOCK_KEY = "lock:job:inventory:prededuct-timeout";

    /**
     * 分布式锁保证多实例只有一个执行。tryLock(0, ...) 表示不等待——
     * 如果另一个实例正在执行，当前实例直接跳过本轮。
     */
    @Scheduled(fixedRate = 60_000) // 每 1 分钟（测试环境快速验证）
    public void releaseExpiredPreDeductions() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 40, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[预扣超时] 其他实例正在执行，跳过本轮");
                return;
            }

            doReleaseExpiredPreDeductions();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[预扣超时] 获取锁被中断");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 实际的扫描和回退逻辑
     * <p>
     * 使用 ZSet 二级索引（inventory:prededuct:index, score=过期时间戳ms）
     * ZRANGEBYSCORE 直接查询即将过期的预扣记录，替代全库 SCAN（O(N) → O(logN+M)）。
     * </p>
     */
    private void doReleaseExpiredPreDeductions() {
        log.debug("[预扣超时] 开始扫描...");
        int releaseCount = 0;

        try {
            // 只回退"已到期"的预扣记录：原实现提前 60s 释放，窗口内支付成功 confirm 会找不到记录
            // （预扣记录物理 TTL 已加 10 分钟缓冲，到期后回调退不再依赖"抢在 TTL 前"）
            long cutoffMs = System.currentTimeMillis();
            java.util.Set<String> expiringOrderIds = stringRedisTemplate.opsForZSet()
                    .rangeByScore(PREDEDUCT_INDEX_KEY, 0, cutoffMs);

            if (expiringOrderIds == null || expiringOrderIds.isEmpty()) {
                return;
            }

            for (String orderIdStr : expiringOrderIds) {
                String key = PREDEDUCT_KEY_PREFIX + orderIdStr;
                int released = releasePreDeduct(key);
                if (released == 0) {
                    // 预扣记录已不存在（惰性过期/已释放/已确认），清理索引中的陈旧 member
                    stringRedisTemplate.opsForZSet().remove(PREDEDUCT_INDEX_KEY, orderIdStr);
                }
                releaseCount += released;
            }

            if (releaseCount > 0) {
                log.info("[预扣超时] 扫描完成: 回退{}条预扣记录", releaseCount);
            }
        } catch (Exception e) {
            log.error("[预扣超时] 扫描异常", e);
        }
    }

    /**
     * 回退单个预扣记录（使用 Lua 脚本保证原子性）
     * <p>
     * 为什么必须用 Lua 脚本而不是分步操作？
     * 如果用分步操作（先 HGETALL → 逐个 INCRBY → DEL），在 HGETALL 和 DEL 之间，
     * 用户可能主动调用 releaseStock 也在释放同一个 orderId 的库存，
     * 导致双重回退——库存凭空增加。
     * Lua 脚本的 HGET + HDEL 是原子的，保证只有一方能成功回退。
     * </p>
     *
     * @param predeductKey inventory:prededuct:{orderId}
     * @return 回退的 SKU 数量
     */
    private int releasePreDeduct(String predeductKey) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(predeductKey);
        if (entries.isEmpty()) {
            return 0;
        }

        // 从 key 提取 orderId（inventory:prededuct:{orderId}），用于 MQ RELEASE 事件
        Long orderId;
        try {
            orderId = Long.parseLong(predeductKey.substring(predeductKey.lastIndexOf(':') + 1));
        } catch (NumberFormatException e) {
            log.warn("[预扣超时] key 格式异常无法提取 orderId, 跳过: {}", predeductKey);
            return 0;
        }

        int count = 0;

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String fieldName = entry.getKey().toString();
            // 过滤 :bucket 辅助字段（只处理 skuId 字段）
            if (fieldName.contains(":bucket")) {
                continue;
            }

            Long skuId;
            int quantity;
            try {
                skuId = Long.parseLong(fieldName);
                quantity = Integer.parseInt(entry.getValue().toString());
            } catch (NumberFormatException e) {
                log.warn("[预扣超时] 跳过脏数据(skuId/数量非数字): key={}, field={}, value={}",
                        predeductKey, fieldName, entry.getValue());
                continue;
            }

            // 获取来源桶号（release.lua 需要桶 Key 作为 KEYS[3]）
            Object bucketNoObj = stringRedisTemplate.opsForHash().get(predeductKey, fieldName + ":bucket");
            int bucketNo = 0;
            if (bucketNoObj != null) {
                try {
                    bucketNo = Integer.parseInt(bucketNoObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("[预扣超时] 桶号脏数据按0处理: key={}, field={}", predeductKey, fieldName);
                }
            }

            String totalKeyStr = totalKey(skuId);
            String bucketKeyStr = bucketKey(skuId, bucketNo);

            // 使用 release.lua 原子回退（传入 totalKey + predeductKey + bucketKey + indexKey）
            Long result = stringRedisTemplate.execute(
                    releaseScript,
                    List.of(totalKeyStr, predeductKey, bucketKeyStr, PREDEDUCT_INDEX_KEY),
                    fieldName, String.valueOf(orderId)
            );

            if (result != null && result > 0) {
                log.info("[预扣超时] 回退库存: predeductKey={}, skuId={}, qty={}, bucket={}",
                        predeductKey, skuId, result, bucketNo);
                count++;
                // Redis 回退成功后发 RELEASE MQ 事件，解锁 MySQL locked_stock
                sendReleaseEvent(orderId, skuId, quantity);
            }
        }

        return count;
    }

    /**
     * 发送 RELEASE 事件到 MQ，解锁 MySQL locked_stock（与 order 服务取消订单的释放链路一致）
     */
    private void sendReleaseEvent(Long orderId, Long skuId, int quantity) {
        long eventId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long eventTime = System.currentTimeMillis();
        try {
            inventoryMapper.insertOutboxEvent(eventId, orderId, skuId, quantity, "RELEASE");
            com.myxhs.inventory.dto.event.InventoryDeductEvent event =
                    com.myxhs.inventory.dto.event.InventoryDeductEvent.builder()
                            .outboxId(eventId)
                            .orderId(orderId)
                            .skuId(skuId)
                            .quantity(quantity)
                            .action("RELEASE")
                            .eventTime(eventTime)
                            .build();
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    "INVENTORY_TOPIC:RELEASE",
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(event)).build(),
                    3000);
            if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                inventoryMapper.markOutboxSent(eventId);
                log.debug("[预扣超时] RELEASE事件发送成功: orderId={}, skuId={}", orderId, skuId);
            } else {
                log.error("[预扣超时] RELEASE事件发送状态异常(等待Outbox补发): orderId={}, skuId={}, status={}",
                        orderId, skuId, sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.error("[预扣超时] RELEASE事件发送失败(等待Outbox补发): orderId={}, skuId={}", orderId, skuId, e);
        }
    }
}
```

### 3.2 逐段要点

1. **扫描方式**：不 SCAN 全库，直接读二级索引 ZSet（`ZRANGEBYSCORE index 0 now`）——O(logN+M)。索引由 prededuct.lua 写入（05 篇）、confirm/release 删除；
2. **只释放"已到期"**（注释原文）：原实现提前 60s 释放（防止记录 TTL 先消失），prededuct.lua 加 10 分钟 TTL 缓冲后不再需要抢跑；
3. **`released == 0` → 清理陈旧 member**：记录没了但索引还在（confirm 半路没 ZREM/惰性过期）→ 顺手清理；
4. **复用 release.lua**：与主动取消/回滚共用同一段原子脚本（"只有一方能成功回退"）；
5. **`sendReleaseEvent` 内联复制了 sendInventoryEvent 的逻辑**（先 insertOutbox 再 syncSend 再 markSent；失败留表补发）——原因：`sendInventoryEvent` 是 `InventoryService` 的 private 方法，Job 类无法复用（**重复代码点**）。**差异点**：本处**未包** `MqTraceHelper.wrapWithTraceId`（对比 `InventoryService.java:635`）——补发的 RELEASE 事件不带 `X-Trace-Id` 头，消费端该条日志缺 traceId（小的可观测性缺口）；
6. **失败语义**：RELEASE 事件发不出 → 留 outbox 行 → OutboxSenderJob 兜底——**Job 内部也依赖另一个 Job**。

### 3.3 边界（与台账交叉）

- **不检查订单支付状态**（也无从知晓）：confirm 失败（问题 4）的已付款订单，会在到期后被本 Job 释放 → 见《工程问题台账》问题 4；
- javadoc 与代码漂移：注释"5 分钟/leaseTime 240s" vs 实际 1 分钟/40s（§2.4）。

---

## 4. Job ②：`InventoryOutboxSenderJob`（Outbox 补发）

### 4.1 全文

```java
/**
 * Outbox 兜底发送任务
 * <p>
 * 扫描 t_inventory_outbox 中未发送的事件，重新投递到 MQ。
 * 解决 syncSend 超时回滚导致的"券已入账但库存退回"双花问题：
 * Outbox 在 Redis 预扣成功后立即写入（与业务操作同步），
 * MQ 发送由此任务异步执行，失败自动重试。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOutboxSenderJob {

    private final InventoryMapper inventoryMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "lock:job:inventory:outbox";
    private static final String INVENTORY_TOPIC = "INVENTORY_TOPIC";
    private static final int BATCH_SIZE = 200;

    @Scheduled(fixedRate = 5000)
    public void sendOutboxEvents() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 4, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(3);
            List<Map<String, Object>> events = inventoryMapper.selectPendingOutbox(cutoff, BATCH_SIZE);
            for (Map<String, Object> row : events) {
                Long eventId = ((Number) row.get("id")).longValue();
                Long orderId = ((Number) row.get("order_id")).longValue();
                Long skuId = ((Number) row.get("sku_id")).longValue();
                String action = (String) row.get("action");
                int quantity = ((Number) row.get("quantity")).intValue();
                // 用 outbox 行创建时间作为 eventTime（保持原始业务时序，供消费者乱序版本检查）
                long eventTime = row.get("created_at") instanceof java.sql.Timestamp
                        ? ((java.sql.Timestamp) row.get("created_at")).getTime()
                        : System.currentTimeMillis();

                // 构造与消费者反序列化格式一致的 InventoryDeductEvent（camelCase），
                // 不能直接序列化 DB 行 Map（snake_case 导致 Consumer 字段全 null → NPE → DLQ）
                com.myxhs.inventory.dto.event.InventoryDeductEvent event =
                        com.myxhs.inventory.dto.event.InventoryDeductEvent.builder()
                                .outboxId(eventId)
                                .orderId(orderId)
                                .skuId(skuId)
                                .quantity(quantity)
                                .action(action)
                                .eventTime(eventTime)
                                .build();

                try {
                    org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                            INVENTORY_TOPIC + ":" + action,
                            MqTraceHelper.wrapWithTraceId(
                                    MessageBuilder.withPayload(objectMapper.writeValueAsString(event)).build()),
                            3000);
                    // 只有真正发送成功才标记，防止发送失败却标记导致事件丢失
                    if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                        inventoryMapper.markOutboxSent(eventId);
                        log.debug("[Outbox] 补发成功: eventId={}, orderId={}, skuId={}, action={}", eventId, orderId, skuId, action);
                    } else {
                        log.warn("[Outbox] 补发状态异常(延后重试): orderId={}, skuId={}, action={}, status={}",
                                orderId, skuId, action, sendResult.getSendStatus());
                        scheduleRetry(eventId, row);
                    }
                } catch (Exception e) {
                    log.warn("[Outbox] 补发失败(延后重试): orderId={}, skuId={}, action={}", orderId, skuId, action, e);
                    scheduleRetry(eventId, row);
                }
            }
        } catch (Exception e) {
            log.error("[Outbox] 扫描异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 补发失败：指数退避（30s→480s 封顶）+ ±20% 抖动，写回 next_retry_time（P2/2026-09-27）。
     * 连续失败 >10 次打 WARN；记录不丢弃——库存事件必须最终送达（对账是另一层兜底）。
     */
    private void scheduleRetry(Long eventId, java.util.Map<String, Object> row) {
        try {
            int attempt = ((Number) row.getOrDefault("retry_count", 0)).intValue() + 1;
            long delaySeconds = com.myxhs.common.mq.RetryBackoffUtils.exponentialSeconds(attempt, 30, 480, 0.2);
            inventoryMapper.markOutboxRetry(eventId, java.time.LocalDateTime.now().plusSeconds(delaySeconds));
            if (attempt > 10) {
                log.warn("[Outbox] 连续补发失败 {} 次: eventId={}, nextRetry={}s", attempt, eventId, delaySeconds);
            }
        } catch (Exception e) {
            log.warn("[Outbox] 写回重试时间失败(下轮仍会重试): eventId={}, error={}", eventId, e.getMessage());
        }
    }
}
```

### 4.2 逐段要点（06 篇已讲过，此处聚焦"作为 Job"的行为）

1. **扫描条件**（mapper SQL）：`status=0 AND created_at < now-3s AND (next_retry_time IS NULL OR <= NOW())`——`now-3s` 避开"正在同步发送中"的行（同步发送超时恰为 3s）；
2. **eventTime 用行的 `created_at`**：补发的消息与首次发送携带**同一 eventTime**——消费端版本门据此判重（06 §5.2③）；
3. **成功才标记**（注释原文）：防"发送失败却标记"导致事件丢失；
4. **退避**：30→480s 封顶 + ±20% 抖动（V3 迁移背景：原固定 5s 无限重试=故障期重试风暴）；
5. **永不丢弃**：连续失败 >10 次只 WARN；对账是下一层兜底。

### 4.3 数据走一遍（一行 outbox 的完整状态机）

| 时刻 | 行状态 | 动作 |
|---|---|---|
| t0 | `status=0, retry_count=0, next_retry_time=NULL` | 业务写入（sendInventoryEvent） |
| t0+5s | 被本 Job 扫到（超过 3s） | 补发 → 成功 → `status=1`（结束） |
| 或：补发失败（t0+5s 起） | `retry_count=1, next_retry_time≈t0+29~41s`（30s±20% 抖动） | 到点后可再扫 |
| 连续失败第 11 次 | WARN 日志 | 记录保留，继续退避（封顶 480s） |

---

## 5. Job ③：`InventoryCompensationJob`（补偿重试）

### 5.1 全文

```java
/**
 * 库存补偿重试任务
 * <p>
 * 扫描 t_inventory_compensation 表中未处理的回滚失败记录，
 * 使用 release.lua 重试回退库存。最多重试 3 次，超出标记为死信。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryCompensationJob {

    private final InventoryMapper inventoryMapper;
    private final InventoryService inventoryService;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> releaseScript;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "lock:job:inventory:compensation";
    /** 补偿类型：1-预扣回滚 2-退款回补 */
    private static final int COMPENSATION_TYPE_REFUND_RESTORE = 2;

    private static final int MAX_RETRY = 3;
    private static final int BATCH_SIZE = 50;

    private static final String TOTAL_KEY_TPL = "inventory:{%d}:total";
    private static final String BUCKET_KEY_TPL = "inventory:{%d}:bucket:";
    /** T-063（2026-08-14）：release.lua 需要 KEYS[4]=index——原补偿调用缺省，预扣 Hash 清空时 ZREM nil 脚本错 */
    private static final String PREDEDUCT_INDEX_KEY = "inventory:prededuct:index";

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_TPL, skuId); }
    private static String bucketKey(Long skuId, int bucketNo) { return String.format(BUCKET_KEY_TPL, skuId) + bucketNo; }

    @Scheduled(fixedRate = 30000)
    public void retryCompensations() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 25, TimeUnit.SECONDS);
            if (!acquired) return;

            doRetryCompensations();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doRetryCompensations() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(30);
        List<Map<String, Object>> records = inventoryMapper.selectPendingCompensation(cutoff, MAX_RETRY, BATCH_SIZE);
        for (Map<String, Object> row : records) {
            Long id = ((Number) row.get("id")).longValue();
            Long orderId = ((Number) row.get("order_id")).longValue();
            Long skuId = ((Number) row.get("sku_id")).longValue();

            int type = row.get("type") == null ? 1 : ((Number) row.get("type")).intValue();
            try {
                if (type == COMPENSATION_TYPE_REFUND_RESTORE) {
                    // 退款回补补偿：调用 refundRestore（累计正增量幂等，重复重试安全）
                    Long userId = row.get("user_id") == null ? null : ((Number) row.get("user_id")).longValue();
                    int quantity = ((Number) row.get("quantity")).intValue();
                    var req = new com.myxhs.inventory.dto.request.RefundRestoreRequest();
                    req.setOrderId(orderId);
                    req.setSkuId(skuId);
                    req.setQuantity(quantity);
                    req.setUserId(userId);
                    inventoryService.refundRestore(req);
                    inventoryMapper.markCompensationResolved(id);
                    log.info("[库存补偿] 退款回补重试成功: orderId={}, skuId={}, qty={}", orderId, skuId, quantity);
                    continue;
                }
                String predeductKey = "inventory:prededuct:" + orderId;
                // 【F-037】从预扣 hash 读取实际来源桶号，而非固定 bucket 0
                Object bucketNoObj = stringRedisTemplate.opsForHash().get(predeductKey, skuId + ":bucket");
                int bucketNo = bucketNoObj != null ? Integer.parseInt(bucketNoObj.toString()) : 0;
                // 使用 release.lua 重试回退（T-063：补齐 KEYS[4]=index；T-065：补齐 ARGV[2]=orderId——
                // 原实现仅传 skuId，ZREM index 时 ARGV[2]=nil 脚本错 → total 已回退但标记失败 → 下周期重复回退超发）
                Long result = stringRedisTemplate.execute(
                        releaseScript,
                        List.of(totalKey(skuId), predeductKey, bucketKey(skuId, bucketNo), PREDEDUCT_INDEX_KEY),
                        String.valueOf(skuId), String.valueOf(orderId));

                if (result != null && result > 0) {
                    inventoryMapper.markCompensationResolved(id);
                    log.info("[库存补偿] 回退成功: orderId={}, skuId={}, qty={}", orderId, skuId, result);
                } else {
                    inventoryMapper.incrementCompensationRetry(id, MAX_RETRY);
                    log.warn("[库存补偿] 回退失败(预扣记录已不存在): orderId={}, skuId={}", orderId, skuId);
                }
            } catch (Exception e) {
                inventoryMapper.incrementCompensationRetry(id, MAX_RETRY);
                log.error("[库存补偿] 重试异常: orderId={}, skuId={}", orderId, skuId, e);
            }
        }
    }
}
```

### 5.2 它为什么存在——两类补偿记录的来龙去脉（例子）

> 定义：**补偿记录 = 一次"失败操作"的持久化重试任务**——操作当场没做成，把"还需要重做"记到 `t_inventory_compensation`（`status=0`），本 Job 负责重做到成功。

**故事一：type=1 预扣回滚**（落表点：`rollbackPreDeduct` 的 catch）

预扣主流程的收尾代码（`InventoryService.doPreDeduct`）：

```java
case 1 -> {
    ...
    // L2: MQ 同步发 MySQL（失败则回滚 Redis 预扣）
    if (!sendInventoryEvent(orderId, skuId, request.getQuantity(), "PRE_DEDUCT")) {
        rollbackPreDeduct(orderId, skuId, request.getQuantity());
        throw new BizException(ResultCode.INTERNAL_ERROR, "库存扣减失败，请重试");
    }
    return true;
}
```

```
t0     Redis 预扣成功（扣减已发生）
t0     发 L2 事件失败 → 预扣整体算失败（用户收到"下单失败，请重试"）
       → 但 Redis 扣减已发生，必须立刻还回：rollbackPreDeduct（重放 release.lua）
t0     回滚也失败（Redis 抖动）→ 这 3 件挂在半空，没人知道要还
       → INSERT t_inventory_compensation (type=1, status=0)        ← 落表
t0+30s 本 Job 扫到 → 重放 release.lua → 成功 → status=1（完结）
       失败 → retry_count+1；第 3 次失败 → status=2（转人工）
```

**故事二：type=2 退款回补**（落表点：`recordRefundRestoreCompensation`）

```
t0     用户退款成功（钱已退）
t0     回补库存：refundRestore 失败（Redis 抖动 / 扩容暂停窗口）
       → 钱退了、货没回来 = 库存少算
       → INSERT (type=2, status=0)（去重：已有待处理记录则不重复记）  ← 落表
t0+30s 本 Job 扫到 → 重放 refundRestore（累计幂等，重放安全）→ status=1
```

| 记录类型 | 何时产生 | 本 Job 重做的动作 |
|---|---|---|
| `type=1 预扣回滚` | 预扣失败后的善后回滚也失败 | 重放 `release.lua` |
| `type=2 退款回补` | 退款时回补库存失败 | 重放 `refundRestore` |

**与 OutboxSenderJob 同套路**：两者都是"持久化失败意图 + 重试到成功"——Outbox 补的是"**事件没发出**"，本 Job 补的是"**库存操作没做成**"。

### 5.3 逐段要点

1. **两类补偿分流**（type 字段，V2 迁移）：
   - `type=2 退款回补`：重放 `inventoryService.refundRestore`（累计幂等，安全）；
   - `type=1 预扣回滚`（默认）：读**真实来源桶号**（F-037 修复，不再写死桶 0）→ 调 release.lua；
2. **cutoff=now-30s**：给"刚落表的记录"留出等待（业务线程可能正在处理）；
3. **重试计数与转人工**：`incrementCompensationRetry(id, MAX_RETRY)` 的 SQL 是 `SET retry_count = retry_count + 1, status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 2 ELSE 0 END`——**第 3 次失败时置 `status=2`（转人工）**；扫描条件 `retry_count < 3` 天然把已转人工的记录剔除；
4. **历史修复注释**（T-063/T-065）值得读——都是"参数不全导致一半成功"的经典坑：漏 KEYS[4] → ZREM nil 脚本错；漏 ARGV[2] → total 已回退但索引没清 → 下轮重复回退**超发**。

### 5.4 数据走一遍（type=1 补偿行）

| 时刻 | 行状态 | 动作 |
|---|---|---|
| 落表 | `status=0, retry_count=0` | 回滚失败时写入（rollbackPreDeduct catch） |
| 第 1 轮（>30s 后） | 扫到（`retry_count < 3`）→ release.lua 重放 | 成功 → `status=1`（已处理，结束） |
| 失败第 1 次 | `retry_count=1, status=0` | 下轮继续 |
| 失败第 2 次 | `retry_count=2, status=0` | 下轮继续 |
| 失败第 3 次 | `retry_count=3`，**`status=2`** | **转人工**（`retry_count < 3` 不再入选；无自动通知——评审点 §2.3⑤） |

---

## 6. Job ④：`InventoryReconcileJob`（对账，L3）

### 6.1 核心代码（节选：handler + doReconcile + locked 重算 + 分桶 + 清理）

```java
/**
 * 库存对账修复任务（L3）（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 3 点执行，对比 Redis 总库存与 MySQL 可用库存。
 * 以 Redis 为准修复 MySQL（因为 Redis 是实时扣减的权威数据源）。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 * <p>
 * 修复策略：
 * 1. Redis 总库存 != MySQL available_stock → 以 Redis 为准更新 MySQL
 * 2. Redis 未初始化 + MySQL 有记录 → 跳过（可能是还未初始化到 Redis）
 * 3. Redis 已初始化 + MySQL 无记录 → 告警（异常情况）
 * </p>
 * <p>
 * 边界条件：
 * 如果对账时有未消费的 MQ 消息（L2 消息积压），以 Redis 为准修复 MySQL 后，
 * 积压的 MQ 消息被消费时可能导致 MySQL 被多扣一次。
 * 这种情况概率极低（凌晨 3 点 MQ 积压），且下一次对账会再次修复。
 * </p>
 */
@XxlJob("inventoryReconcileJob")
public void reconcile() {
    try {
        int repairCount = doReconcile();
        XxlJobHelper.handleSuccess("对账修复完成，修复 " + repairCount + " 条");
    } catch (Exception e) {
        log.error("[库存对账] 执行异常", e);
        XxlJobHelper.handleFail("库存对账异常: " + e.getMessage());
    }
}

public int doReconcile() {
    // 手动端点与 XXL 调度可能重叠：抢不到锁直接跳过本轮（原实现无锁，全量对账并发跑会互相覆盖）
    org.redisson.api.RLock lock = redissonClient.getLock("myxhs:lock:job:inventory:reconcile");
    if (!lock.tryLock()) {
        log.info("[库存对账] 已有实例在执行，跳过本轮");
        return 0;
    }
    try {
    log.info("[库存对账] 开始执行...");
    long startTime = System.currentTimeMillis();
    int repairCount = 0;

    List<Inventory> inventories = inventoryMapper.selectList(
            new LambdaQueryWrapper<Inventory>().eq(Inventory::getDeleted, 0));
    log.info("[库存对账] 待对账SKU数: {}", inventories.size());

    for (Inventory inventory : inventories) {
        String totalKey = totalKey(inventory.getSkuId());
        String redisTotalStr = stringRedisTemplate.opsForValue().get(totalKey);

        if (redisTotalStr == null) {
            log.debug("[库存对账] Redis未初始化: skuId={}", inventory.getSkuId());
            continue;
        }

        int redisTotal;
        try {
            redisTotal = Integer.parseInt(redisTotalStr);
        } catch (NumberFormatException e) {
            log.warn("[库存对账] 跳过脏数据(total非数字): skuId={}, value={}", inventory.getSkuId(), redisTotalStr);
            continue;
        }
        int mysqlAvailable = inventory.getAvailableStock();

        if (redisTotal != mysqlAvailable) {
            int oldAvailable = mysqlAvailable;
            // 目标 UPDATE 只更新 available_stock：不用 updateById 全字段盲写
            // （selectList 快照的 locked_stock 可能是旧值，盲写会回滚并发 L2 的 locked 变更 → 幻影锁复活）
            inventoryMapper.updateAvailableStockOnly(inventory.getSkuId(), redisTotal);
            repairCount++;
            log.info("[库存对账] 修复: skuId={}, mysql: {}→{} (以Redis为准)",
                    inventory.getSkuId(), oldAvailable, redisTotal);
        }

        // 【M9】分桶完整性检查：各桶之和 == 总库存
        reconcileBuckets(inventory.getSkuId());
    }

    // 【新增】locked_stock 对账：以 Redis 在途预扣（inventory:prededuct:* Hash）为权威重算。
    // 修复缺口：预扣记录丢失/TTL 过期/确认失败都会留下"幽灵锁"，而 reinitStock 会把 locked 计入总库存
    // → 可售虚增。方向保守：预扣记录不存在即视为 0（宁可少算可售，不会多卖）。
    repairCount += reconcileLockedStock(inventories);

    // 清理 7 天前的预扣幂等占位（原实现成功预扣的占位永久保留 → 表线性增长；
    // 幂等只需覆盖"预扣 TTL + MQ 重投窗口"，7 天后无重投可能）
    try {
        int purged = inventoryMapper.deleteStalePredeductIdem(
                java.time.LocalDateTime.now().minusDays(7), 2000);
        if (purged > 0) {
            log.info("[库存对账] 清理过期预扣幂等占位 {} 行", purged);
        }
    } catch (Exception e) {
        log.warn("[库存对账] 清理过期预扣幂等占位失败(下轮重试)", e);
    }

    // outbox（7 天）/ 补偿表（90 天）保留：同样日切一次 + 有界排空
    purgeTablesDaily();

    long elapsed = System.currentTimeMillis() - startTime;
    log.info("[库存对账] 完成: 对账{}个SKU, 修复{}条, 耗时{}ms",
            inventories.size(), repairCount, elapsed);

    return repairCount;
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}

/**
 * locked_stock 对账：扫描在途预扣记录（按 orderId 的 Hash，TTL 内即"未决"），
 * 按 SKU 汇总后与 MySQL locked_stock 比对并修正。
 * <p>
 * 扫描量有界：预扣 Hash 的 TTL=预扣有效期（1800s），因此 key 数 ≈ 在途订单数；
 * 每小时一轮，成本可控。方向保守：预扣记录缺失即视为 0（宁可少算 locked，不会多卖）；
 * 注意：SCAN 在 Redis Cluster 下只覆盖单个节点，若未来上 Cluster 需改为逐节点扫描（当前单实例+Sentinel 无影响）；
 * 若 L2 事件滞后于本次扫描，locked 会在事件到达后被再次抬高，最多一个事件的量，下轮对账收敛。
 * @return 修复条数
 * </p>
 */
private int reconcileLockedStock(List<Inventory> all) {
    java.util.Map<Long, Integer> lockedBySku = new java.util.HashMap<>();
    try (var cursor = stringRedisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
            .match("inventory:prededuct:*").count(500).build())) {
        cursor.forEachRemaining(key -> {
            if (key.endsWith(":index")) {
                return;   // inventory:prededuct:index 是 ZSet 索引，不是预扣 Hash
            }
            try {
                java.util.Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
                for (java.util.Map.Entry<Object, Object> e : entries.entrySet()) {
                    String field = String.valueOf(e.getKey());
                    if (field.endsWith(":bucket")) {
                        continue;   // 桶号字段不是数量
                    }
                    Long skuId = Long.valueOf(field);
                    int qty = Integer.parseInt(String.valueOf(e.getValue()));
                    lockedBySku.merge(skuId, qty, Integer::sum);
                }
            } catch (Exception ex) {
                log.warn("[库存对账] 预扣记录解析失败(跳过): key={}", key, ex);
            }
        });
    } catch (Exception e) {
        log.error("[库存对账] 在途预扣扫描失败, 本轮跳过 locked 对账", e);
        return 0;
    }

    int repaired = 0;
    for (Inventory inventory : all) {
        int expected = lockedBySku.getOrDefault(inventory.getSkuId(), 0);
        int current = inventory.getLockedStock() == null ? 0 : inventory.getLockedStock();
        if (current != expected) {
            inventoryMapper.updateLockedStockOnly(inventory.getSkuId(), expected);
            repaired++;
            log.info("[库存对账] locked 修复: skuId={}, {}→{}（以在途预扣为准）",
                    inventory.getSkuId(), current, expected);
        }
    }
    log.info("[库存对账] locked 对账完成: 在途SKU数={}, 修复={}", lockedBySku.size(), repaired);
    return repaired;
}

/**
 * 【M9】分桶完整性对账：各桶库存之和 == 总库存
 * 不一致时以分桶之和为准修正总库存。
 * <p>
 * 【原子化修复】使用 Lua 脚本在单个原子执行单位内完成求和+对比+设置，
 * 替代 Java 侧"读 total → 循环 GET 各桶 → SET"的非原子序列——
 * 读后发生的 preDeduct/release 会被盲 SET 覆盖（幻影回滚在途预扣）。
 * </p>
 */
private void reconcileBuckets(Long skuId) {
    String countStr = stringRedisTemplate.opsForValue()
            .get(String.format("inventory:bucket:count:{%d}", skuId));
    if (countStr == null) return;
    int bucketCount;
    try {
        bucketCount = Integer.parseInt(countStr);
    } catch (NumberFormatException e) {
        log.warn("[库存对账] 跳过脏数据(bucketCount非数字): skuId={}, value={}", skuId, countStr);
        return;
    }

    List<String> keys = new java.util.ArrayList<>(1 + bucketCount);
    keys.add(String.format("inventory:{%d}:total", skuId));
    for (int i = 0; i < bucketCount; i++) {
        keys.add(String.format("inventory:{%d}:bucket:", skuId) + i);
    }

    Long fixed = stringRedisTemplate.execute(reconcileBucketsScript, keys);
    if (fixed != null && fixed == 1) {
        log.warn("[库存对账] 分桶总量不一致已修正(原子Lua): skuId={}", skuId);
    }
}

/**
 * 库存 outbox / 补偿表保留策略（日切一次，有界排空）
 */
private void purgeTablesDaily() {
    try {
        String marker = "myxhs:inventory:cleanup:" + java.time.LocalDate.now();
        boolean first = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(marker, "1", java.time.Duration.ofHours(25)));
        if (!first) {
            return;
        }
        int outbox = 0;
        for (int i = 0; i < 10; i++) {
            int n = inventoryMapper.deleteSentOutbox(java.time.LocalDateTime.now().minusDays(7), 2000);
            outbox += n;
            if (n < 2000) {
                break;
            }
        }
        int comp = 0;
        for (int i = 0; i < 10; i++) {
            int n = inventoryMapper.deleteSettledCompensation(java.time.LocalDateTime.now().minusDays(90), 2000);
            comp += n;
            if (n < 2000) {
                break;
            }
        }
        if (outbox + comp > 0) {
            log.info("[库存对账] 保留策略清理: outbox(7d)={} 条, compensation(90d)={} 条", outbox, comp);
        }
    } catch (Exception e) {
        log.error("[库存对账] 保留策略清理失败(明日重试)", e);
    }
}
```

### 6.2 逐段要点

1. **对账三件事 + 清理**：
   - `available`：Redis total ↔ MySQL available（Redis 为准，**只更新单列**——防"幻影锁复活"，注释原文）；
   - `locked`：SCAN 在途预扣 Hash 重算求和 → 修 MySQL locked（保守方向：记录不存在 = 0，**宁可少算可售，不会多卖**）；
   - 分桶求和：原子 Lua 校验（防"读后写"覆盖在途变更）；
   - 清理：幂等占位 7 天 / outbox 7 天 / 补偿 90 天（日切 marker + 有界排空 10×2000）。
2. **调度**：XXL-Job（handler `inventoryReconcileJob`）+ Redisson 锁——防"手动端点 × 调度"重叠（注释原文："原实现无锁，全量对账并发跑会互相覆盖"）；
3. **已知边界**（本类注释自述 + 台账问题 2）：
   - 与在线写无屏障（丢更新/假差异）——见《工程问题台账》问题 2；
   - MQ 积压时"以 Redis 为准修复"后、滞后消息到达可能让 MySQL 再变一次（下轮对账再修）；
   - SCAN 在 Cluster 下只覆盖单节点（当前单实例+Sentinel 无影响）。

---

## 7. 边界与口径汇总

### 7.1 数据保留策略一览（全模块）

| 数据 | 保留 | 清理者 |
|---|---|---|
| 预扣记录 Hash | 物理 TTL = 到期 + 600s（prededuct.lua） | Redis 到期 + 超时 Job/confirm/release 主动删 |
| L1 msgId 幂等键 | 24h | TTL |
| 事件版本键 | 24h | TTL |
| 退款累计键 | 30 天 | TTL |
| 预扣幂等占位（t_inventory_prededuct_idem） | 7 天 | ReconcileJob（deleteStalePredeductIdem） |
| outbox（已发送） | 7 天 | ReconcileJob（purgeTablesDaily） |
| 补偿表（已处理） | 90 天 | ReconcileJob（purgeTablesDaily） |

### 7.2 四个 Job 的失败语义对照

| Job | 失败/没跑 | 靠谁兜 |
|---|---|---|
| Timeout | 幽灵锁 | 下轮自己重试；释放在途有 release.lua 幂等 |
| Outbox | MySQL 滞后 | 下轮自己重试（退避）；最终 L3 |
| Compensation | 补偿堆积 | 下轮自己重试；第 3 次失败即转人工（**无通知**，评审点） |
| Reconcile | 账目漂移 | 下轮自己重试 |

### 7.3 与台账的交叉引用

- **问题 2**：对账与在线写"无屏障"（本 Job 的已知边界）；
- **问题 4**：本 Job 的超时释放"不查支付状态"，与 confirm 失败叠加 → 已付款订单被释放；
  - **2026-10-14 已修复**：释放前检查"确认标记"（`inventory:confirmed:{orderId}`，confirm 找不到预扣记录时写入）→ 命中改判"补确认"而非释放（见 07 §3.5② 更新与《订单-讲解/03B-异步下单的语义与收口.md》§七）；
- **问题 5**：与 Job 无关（订单侧补偿自循环），但同属"兜底机制自身的问题"族。

---

## 8. 检查题（2 道）

1. `PreDeductTimeoutJob` 的 `sendReleaseEvent` 为什么没有直接复用 `InventoryService.sendInventoryEvent`？两者的行为是否完全一致（少了什么、多了什么）？
2. `ReconcileJob` 修 `available` 时为什么坚持"只更新单列"（`updateAvailableStockOnly`）而不是用 `updateById` 整行写回？如果整行写回，会出什么事故（提示：selectList 快照的 locked_stock 是旧值，而 L2 在并发改它）。
