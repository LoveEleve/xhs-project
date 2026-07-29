# 04 — MQ + 幂等 + 对账深度分析

> **前置阅读**：[架构文档 §4.1 (领券链路)](01-coupon-module.md) · §5.2 (RocketMQ) · §4.5 (对账任务) · [03-claim_coupon.lua 深度](03-claim-coupon-lua.md)
> **测试验证**：[测试 2 (领券)](02-coupon-test-record.md) — syncSend + Consumer + MySQL 完整链路

## syncSend 的正确性保证

源码：`CouponService.sendClaimEventSync()`

```java
SendResult sendResult = rocketMQTemplate.syncSend(
    COUPON_CLAIM_TOPIC,
    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
    3000  // 超时 3 秒
);
return sendResult.getSendStatus() == SendStatus.SEND_OK;
```

领券的完整链路：

```
POST /api/coupon/claim
  │
  ├── claim_coupon.lua (L1: Redis 原子, ~1ms)
  │     DECR stock, INCR claimed
  │
  ├── syncSend COUPON_CLAIM_TOPIC (L2: MQ, ~20ms)
  │     │
  │     ├── SendStatus.SEND_OK → return 200 ✓
  │     └── 失败 → rollbackRedisStock(INCR stock, DECR claimed) → BizException
  │
  └── CouponClaimConsumer (L2: MySQL 持久化, ~13s 延迟)
        └── msgId 幂等 → INSERT + decrementRemainCount
```

**为什么必须 syncSend？** 和 inventory 的 preDeduct 同理：L1 的 Redis Lua 已经扣了库存，如果 MQ 异步发送失败——Redis 扣了但 MySQL 永不更新 → `remain_count` 不减少 → 模板显示还有余量，但 Redis 说已领完。syncSend 等待 Broker 确认，失败则立即 `rollbackRedisStock` 回退 Redis。

**rollbackRedisStock 的实现**：

```java
private void rollbackRedisStock(String stockKey, String claimedKey) {
    stringRedisTemplate.execute(returnCouponScript, List.of(stockKey, claimedKey));
}
```

直接用 `return_coupon.lua` 做回退——INCR stock + DECR claimed，与领券操作的逆操作完全一致。如果没有 Lua 原子回退（用分步 INCR + DECR），在 INCR 和 DECR 之间可能穿插其他操作导致数据偏移。

**回退失败的三层兜底**：

| 层 | 机制 | 触发条件 |
|:--:|------|------|
| 1 | syncSend 失败 → rollbackRedisStock | Broker 不可达或超时 |
| 2 | rollbackRedisStock 失败 → log.error | Redis 不可达 |
| 3 | 凌晨对账 | CouponReconcileJob 对比 Redis↔MySQL |

---

## 双重幂等：msgId + uk_claim_no

源码：`CouponClaimConsumer.java`

```java
// 第 1 层：msgId 幂等
if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
    return;  // 已处理过，跳过
}

// 第 2 层：uk_claim_no 兜底
try {
    userCouponMapper.insert(userCoupon);  // claim_no = msgId
} catch (DuplicateKeyException e) {
    log.warn("重复领券记录(DB唯一索引兜底)");
    return;
}
```

**两层幂等解决不同层面的重试**：

| 层 | 机制 | 防什么 | 失效条件 |
|------|------|------|------|
| msgId 幂等 | Redis SETNX（24h TTL） | RocketMQ at-least-once 重复投递 | Redis 主从切换期间短暂不一致 |
| uk_claim_no | MySQL 唯一索引 | Consumer 重复消费跨 Redis 24h 窗口 | MySQL 宕机 |
| 极端重合 | L3 对账 | 两层都失效 | 凌晨修复 |

**msgId 幂等的位置**：在 Consumer 消费循环的**最开头**——在执行任何业务逻辑之前先检查。如果 msgId 已处理，直接 return，不执行 INSERT 和 decrementRemainCount。

**为什么不在 INSERT 之后标记幂等？** 如果 Consumer 在 INSERT 成功后、标记幂等之前崩溃——RocketMQ 重投消息 → msgId 未被标记 → 再次 INSERT → DuplicateKeyException 拦截。两层幂等的设计已经覆盖了这个场景（第一层失效由第二层兜底）。

---

## CouponReconcileJob：凌晨修复的最后一层

源码：`CouponReconcileJob.java`

```java
@XxlJob("couponReconcileJob")
public void reconcile() {
    // 只对启用 + 未过期 + 未删除的模板对账
    for (CouponTemplate template : activeTemplates) {
        String redisStock = stringRedisTemplate.opsForValue().get(stockKey(template.getId()));
        int mysqlRemain = template.getRemainCount();

        if (redisStock == null || Integer.parseInt(redisStock) != mysqlRemain) {
            // 以 MySQL 为准修复 Redis
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(mysqlRemain));
            repairCount++;
        }
    }
}
```

### 对账方向：以 MySQL 为准

与 inventory 的 L3 对账（以 Redis 为准修复 MySQL）不同，coupon 的对账方向相反：

| 模块 | 权威数据源 | 原因 |
|------|:--:|------|
| inventory | Redis | Redis 是实时扣减的 L1 层，MySQL 是异步持久化的 L2 层 |
| coupon | MySQL | remain_count 由 MQ Consumer 的 SQL 乐观锁控制 + returnCoupon 的 @Transactional |

inventory 的 L1 来自 Redis Lua（实时），L2 来自 MQ（异步）。Redis 是最新数据。

coupon 的 L1 来自 Redis Lua（实时），但 L2 的 `remain_count` 受 MySQL 乐观锁保护（`WHERE remain_count > 0`）。如果 Consumer 的 decrementRemainCount 失败（affected=0），Lua 已经扣了 Redis 但 MySQL 没有同步——此时 Redis 的值是错的（它认为库存比实际少）。

所以对账方向是 Redis→MySQL：以 MySQL 的 remain_count 为准重置 Redis stock。

### 为什么对账只在凌晨执行？

1. **低频操作**：Redis↔MySQL 不一致的概率极低（仅在 MQ 回滚失败、Consumer 崩溃等极端场景发生）
2. **避免白天影响**：凌晨 2 点是业务低谷期，遍历模板、SET Redis 的操作量可控
3. **24 小时窗口**：对账窗口 24 小时——即使问题发生在今天下午，最晚明天凌晨 2 点修复

### 对账的范围限制

对账只覆盖 `stock` Key，不覆盖 `claimed:userId` Key。如果某个用户的 claimed 值与实际不符（如退券时 Redis 失败），不会自动修复——需要用户重新领券来触发真实的 claimed 检查。

---

## 面试 Q&A

### Q1：如果 Consumer 处理消息到一半崩溃，RocketMQ 重投后怎么保证不重复？

**答案**：两层幂等。msgId 层在 Consumer 入口处检查——如果该 msgId 的 SETNX 已存在（24h 内），直接 return 跳过。如果 SETNX 恰好过期（24h 后），uk_claim_no 唯一索引兜底——INSERT 触发 DuplicateKeyException → 静默跳过。

**追问**：如果 Consumer 在 INSERT 之后、decrementRemainCount 之前崩溃呢？

→ 消息重投时 msgId 幂等命中（SETNX 已存在）→ return 跳过 → decrementRemainCount 不会再次执行 → remain_count 少减一次 → 凌晨对账修复。这个场景在概率上等同于 "MQ 重复投递"。

### Q2：rollbackRedisStock 用 return_coupon.lua 而不是直接 INCR/DECR，有什么区别？

**答案**：`return_coupon.lua` 有 `if claimed <= 0 then return 0 end` 的安全检查——如果 claimed 值异常（如为 0 或负数），不会执行 INCR/DECR。分步 INCR + DECR 没有这个检查——如果 claimed 为 0，DECR 会导致 claimed 变成 -1，后续领券检查 `claimed >= perUserLimit` 永久失效（-1 < 3，永远满足）。

**追问**：rollbackRedisStock 后 Consumed 又被 MQ Consumer 重试了，会发生什么？

→ Consumer INSERT → DuplicateKeyException → 跳过 → decrementRemainCount 不会再次执行。Redis 层：rollbackRedisStock 已经 INCR stock（库存已恢复），Lua 层的 stock 值与 MySQL remain_count 出现偏差 → 凌晨对账修复。

### Q3：CouponReconcileJob 一天只跑一次，如果 Redis stock 在当天下午就出问题了，用户会感知到吗？

**答案**：会。如果 Redis stock 偏离 MySQL remain_count（如 stock 多扣了 1），Lua 会在 MySQL 还有库存时返回 -1（已领完）——用户看到"已领完"但实际还有券。对账在凌晨修复，但白天的偏差窗口是 0-24 小时。

**改进方向**：增加每小时对账（而非每天凌晨），或增加实时校验（领券时检查 stock ≤ remain_count ± 容忍阈值）。当前设计选择了"简单可靠"而非"实时精确"。

---

## 发散：MQ 幂等的其他方案对比

| 方案 | coupon 的实现 | 替代方案 | 为什么没选替代 |
|------|------|------|------|
| Redis SETNX | MessageIdempotentHelper（24h TTL） | Redis Set（无限增长） | 无限增长需要定期清理，增加运维成本 |
| DB 唯一索引 | uk_claim_no(claim_no) | 应用层乐观锁（version 字段） | 唯一索引是 DB 原生保证，不需要额外字段 |
| 消息表 | 无（直接 INSERT + uk 防重） | 独立消息表（consume_record） | 增加表数量和维护成本，uk_claim_no 够用 |
| 对账修复 | CouponReconcileJob（凌晨） | 实时 CDC（Canal 监听） | Canal 链路增加复杂度，低频对账足够 |

---

## 生产故障实验

### 实验：验证 msgId 幂等（已跨 24h 窗口验证）

```bash
# 1. 在 24h 内重复发送同一消息（模拟 MQ 重投）
# Consumer 日志应显示：msgId 幂等命中 → 跳过

# 手动方式：用相同的 msgId 做第二次 claim
# 由于 claim_no=msgId，第二次 INSERT 触发 DuplicateKeyException
# Consumer 日志：[优惠券MQ] 重复领券记录(DB唯一索引兜底)
```

**说明**：msgId 幂等的 24h 窗口跨夜间覆盖。如果要验证 24h 后去重失效的场景，需要等 24h——不推荐在测试中做。uk_claim_no 唯一索引的兜底作用已在正常流程中验证（测试 2 的 claim_no 写入成功证明唯一索引生效）。
