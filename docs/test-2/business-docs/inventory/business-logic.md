# my-xhs-inventory 业务逻辑分析

## 一、库存状态

```
available_stock: 可用库存(Redis权威数据源)
locked_stock:    已锁定(预扣确认后)
freezing_stock:  TCC冻结中(Try→Confirm/Cancel)
total = available_stock + locked_stock + freezing_stock
```

## 二、预扣→确认→释放流程

### 预扣 (I02)
Redis Lua原子多桶递减→available_stock -= qty→写预扣记录(inventory:prededuct:{orderId}, 30min TTL)→Outbox写t_inventory_outbox→MQ发送PRE_DEDUCT

### 确认 (I03)
删预扣记录→MQ CONFIRM → Consumer: locked_stock += qty, available_stock -= qty

### 释放 (I04)
删预扣记录→MQ RELEASE → Consumer: available_stock += qty

### 超时释放
PreDeductTimeoutJob: 30min未确认/释放的预扣→自动释放

## 三、TCC分布式事务

### 使用场景
order创建→Feign inventory I08 tryDeductStock→成功→COMMIT→I09 confirm→失败→ROLLBACK→I10 cancel

### Fence表防悬挂
```
tx1: Try(xid=tx1)→插入status=1→成功→冻结库存
tx2: Cancel(xid=tx1)(late)→插入status=3→SUSPENDED→跳过
tx3: Confirm(xid=tx1)→UPDATE 1→2→确认扣减
```

### 空回滚
```
tx1: Cancel(xid=tx1)→INSERT status=3成功→SKIP(无Try记录)
tx2: Try(xid=tx1)→INSERT status=1→SUSPENDED(Cancel已存在)
```

## 四、库存初始化

### 初始化 (I01)
Redis桶分片: `inventory:{skuId}:bucket:{0..N-1}` + `inventory:{skuId}:total`

### 重建 (I05)
SCAN清空所有桶→从MySQL t_inventory读取→重建Redis桶分片

## 五、查询策略

```
I06 GET /stock/{skuId}
  → CacheAside: Redis inventory:{skuId}:total
    → 命中 → 返回
    → 未命中 → MySQL SELECT available_stock FROM t_inventory
      → 回写Redis + 返回
```
