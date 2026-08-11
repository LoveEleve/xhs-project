# Inventory 模块 Review

## 总体
库存模块整体设计扎实：分桶 + Lua 原子预扣 + 三级一致性(L1 Redis/L2 MQ/L3 对账) + Outbox + 动态扩容。

## 分布式 / 一致性
1. **幂等完备**：消费端 msgId 去重(MessageIdempotentHelper) + 失败 removeMark 允许重投 +
   Lua 预扣幂等(PREDEDUCT_KEY 重复→-1)。rebalance/重投安全，防超卖。好。
2. **[中] pseudoOrderId = fold-hash(orderNo) 作预扣幂等键**（与 order 的 derivePseudoOrderId 一致，63bit）。
   跨订单碰撞概率极低但非零；且依赖 orderNo 全局唯一（order 侧 Redis INCR 故障降级随机时会撞号 → 幂等键串号）。
   属设计风险点，建议后续用真实 orderId。
3. **[低] resizeBuckets 锁 TTL(10s) < 暂停 TTL(30s)**（InventoryService.java:622,631）
   若扩容执行 >10s，锁过期后另一实例可能并发扩容；暂停标记 30s 内虽挡住预扣，但并发 resize 可能互相覆盖。
   低概率，建议锁 TTL ≥ 暂停窗口。

## 超卖防护
4. **getStock Redis 数据缺失时不回填 MySQL**（InventoryService.java:500-506）—— 避免在途预扣被 MySQL 快照"回滚"导致超卖，要求显式 reinit。正确，但需运维意识。
5. 释放回退到桶0，分布偏差由对账均衡 —— 可接受。

## 工程
6. preDeduct/release/confirm 同步发送 MQ + Outbox + 失败回滚/补偿表 —— 一致性强，设计成熟。
7. 动态扩容窗口用 ResizeInProgressException + MQ 重试兜底 —— 合理。
