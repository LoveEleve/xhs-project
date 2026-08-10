# my-xhs 分布式事务方案

> 四套自实现机制，零 Seata 依赖

---

## 一、总览

| 机制 | 服务 | 解决问题 |
|------|------|------|
| **TCC + Fence** | inventory | 库存预扣的 Try/Confirm/Cancel 三阶段 + 空回滚/防悬挂 |
| **事务消息 + 本地消息表** | order | 下单本地事务与"通知下游"原子绑定，防超卖 |
| **Outbox 模式** | coupon / inventory | MQ发送可靠性："先落库再发送，Job兜底补发" |
| **本地消息表（推模式）** | content → home | Feed推送可靠性 + 断点续推 |

---

## 二、TCC + Fence（库存）

**实现**: `InventoryTccService.java`

### Try — 冻结库存
1. `tccFenceService.tryFence()` 幂等/防悬挂检查
2. `SUSPENDED`（Cancel先到）→ 拒绝
3. `DUPLICATE`（重复Try）→ 跳过
4. `inventoryMapper.tryFreeze()`: `available_stock -= qty, freezing_stock += qty`
5. 写 `t_tcc_freeze_detail` 明细

### Confirm — 确认扣减
1. `confirmFence()` 状态 1→2
2. `confirmFreeze()`: `freezing_stock -= qty`

### Cancel — 解冻
1. `cancelFence()` — 空回滚处理
2. 插入成功(Try未执行) → `SKIP`
3. 乐观锁 `UPDATE WHERE status=1` → 1→3 → `EXECUTE`
4. `cancelFreeze()`: `freezing_stock -= qty, available_stock += qty`

### 超时兜底
`TccTimeoutJob.java`: `@Scheduled(60s)`, 扫超10分钟冻结明细, 逐条Cancel解冻

### Fence表
`t_tcc_fence(xid, branch_id PK)`: status 1=Try/2=Confirm/3=Cancel。空回滚防悬挂。
`t_tcc_freeze_detail(xid, branch_id, sku_id PK)`: 明细状态1/2/3

---

## 三、事务消息 + 本地消息表（下单）

**实现**: `OrderTransactionListener.java` + `LocalMessageRetryJob.java`

### 流程
1. `OrderService.createOrder()` → `sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)` 半消息
2. Broker回调 `executeLocalTransaction()`:
   - `@Transactional`: INSERT t_order + t_order_item + **t_local_message**(transaction_id=orderNo) 同事务
   - 成功 → COMMIT, 失败 → ROLLBACK
3. Broker超60s未确认 → `checkLocalTransaction()`:
   - 查 `t_local_message WHERE transaction_id=orderNo`
   - 有记录 → COMMIT, 无 → ROLLBACK

### 兜底
`LocalMessageRetryJob`: 每30s扫status=0/2本地消息重发, 指数退避30s×2^n, 5次进死信
`deadLetterScanJob`: 每小时重投死信3次

---

## 四、Outbox 模式（券 + 库存）

**实现**: `CouponService.sendClaimEventSync()` / `InventoryService.sendInventoryEvent()`

### 流程
1. `INSERT t_coupon_outbox(claim_no, status=0)` — 落库 (claim_no幂等)
2. `syncSend(COUPON_CLAIM_TOPIC)` — 发MQ
3. 成功 → `markOutboxSent(claim_no)`
4. 超时/异常 → Service回滚Redis → outbox行保留status=0

### 兜底
`CouponOutboxSenderJob.java`: `@Scheduled(5s)`, 扫status=0且>3s的outbox重新投递
claimNo贯穿 outbox→MQ→消费 三环节: `uk_claim_no`唯一索引兜底去重

### 库存Outbox
`t_inventory_outbox`: `uk_order_sku`唯一索引, action=PRE_DEDUCT/CONFIRM/RELEASE
`InventoryOutboxSenderJob.java`: `@Scheduled(5s)` 类似逻辑

---

## 五、本地消息表 + 断点续推（Feed）

**实现**: `NoteService.publishNote()` → `FeedPushConsumer.java`

### 流程
1. `@Transactional`: INSERT t_note + `t_local_message(topic=FEED_TOPIC, status=0)` 同事务
2. `asyncSend(FEED_TOPIC)` → `onSuccess` → `markSent`
3. Consumer: Pipeline ZADD批量推送粉丝收件箱
4. 断点续推: `myxhs:feed:push:progress:{localMsgId}` Redis进度, 崩溃后从cursor恢复

### 兜底
`FeedMessageRetryJob.java`: `@Scheduled(30s)`, 3次重试→死信
`@Scheduled(60s)`: 扫push_status IN(0,1)重投, Consumer断点续推

---

## 六、对比总结

| 维度 | TCC+Fence | 事务消息 | Outbox | 本地消息表 |
|------|:--:|:--:|:--:|:--:|
| 一致性 | 最终 | 强一致(半消息) | 最终 | 最终 |
| 复杂度 | 🔴 高 | 🟡 中 | 🟢 低 | 🟢 低 |
| 回滚 | ✅ Fence空回滚 | ✅ Broker Rollback | ⚠️ Job重试+幂等 | ⚠️ 重试+死信 |
| 兜底 | TccTimeoutJob | LocalMessageRetryJob | OutboxSenderJob(5s) | FeedMessageRetryJob |
| 幂等 | xid+branch_id PK | transaction_id | uk_claim_no | msgId |
