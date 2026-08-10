# my-xhs-inventory 架构分析

## 一、服务拓扑

```
端口: 19005
Feign调用方: order(库存预扣/确认/释放)、product(查询库存)
MQ消费者: InventoryDeductConsumer(INVENTORY_TOPIC)
Job: PreDeductTimeoutJob、TccTimeoutJob、InventoryOutboxSenderJob、InventoryCompensationJob
```

## 二、TCC + Fence 机制

### Try — 冻结库存 (I08)
1. `TccFenceService.tryFence()`: INSERT t_tcc_fence(xid, branch_id, status=1)
   - DuplicateKeyException → status=3 → **悬挂判定**(Cancel先到)→ SUSPENDED
   - DuplicateKeyException → status≠3 → **幂等命中** → DUPLICATE
2. `inventoryMapper.tryFreeze()`: available_stock -= qty, freezing_stock += qty
3. 写入 t_tcc_freeze_detail(冻结明细)

### Confirm — 确认扣减 (I09)
1. `confirmFence()`: 乐观锁 UPDATE t_tcc_fence SET status=2 WHERE status=1
2. `confirmFreeze()`: freezing_stock -= qty
3. 更新明细 status 1→2

### Cancel — 解冻 (I10)
1. `cancelFence()`: 空回滚处理
   - INSERT status=3 成功 → **空回滚**(Try未执行) → SKIP
   - 冲突 status=2 → REJECTED_CONFIRMED
   - 冲突 status=1 → 乐观锁 UPDATE 1→3 → EXECUTE
2. `cancelFreeze()`: freezing_stock -= qty, available_stock += qty
3. 明细 status 1→3

### 超时兜底
`TccTimeoutJob.java`: @Scheduled(60s) + Redisson锁
- 扫描 t_tcc_freeze_detail status=1 超10分钟
- 逐条 cancelFence → 解冻

## 三、Outbox 模式

### 流程 (I02)
1. `inventoryMapper.insertOutboxEvent()`: ON DUPLICATE KEY UPDATE 幂等
2. `syncSend(INVENTORY_TOPIC:PRE_DEDUCT)`
3. 成功 → `markOutboxSent`
4. 超时/异常 → `cancelOutboxEvent`(防止Job重发已回滚事件)

### 兜底
`InventoryOutboxSenderJob.java`: @Scheduled(5s)，扫描status=0超3s事件重发

## 四、桶分片库存

### 设计
- Redis桶分片: `inventory:{skuId}:bucket:{0..N-1}`
- Lua原子: 多桶原子扣减，防热点并发
- 初始化: I01根据stock数量动态确定桶数

### 三级扣减保证
1. **预扣**(Redis Lua原子): 多桶递减 + 写预扣记录(30min TTL)
2. **确认**(MQ异步): locked_stock -= qty
3. **释放**(MQ异步): available_stock += qty

## 五、安全机制

| 机制 | 说明 |
|------|------|
| X-Internal-Call | 内部接口校验 internal.token |
| X-Admin-Call | 管理接口校验 admin.token |
| RateLimit | I05 reinit限流 |

## 六、补偿体系

| 机制 | 触发 | 作用 |
|------|------|------|
| PreDeductTimeoutJob | @Scheduled | 30min超时自动释放预扣 |
| TccTimeoutJob | @Scheduled(60s) | 10min超时取消TCC冻结 |
| InventoryOutboxSenderJob | @Scheduled(5s) | Outbox重投 |
| InventoryCompensationJob | @XxlJob | 补偿表 retry_count≤5 |
