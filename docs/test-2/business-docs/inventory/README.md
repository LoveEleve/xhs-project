# my-xhs-inventory 库存服务

> 10个端点 | InventoryController(/api/inventory) + TccController | 端口19005
> 四套分布式事务核心 — TCC+Fence + Outbox + 桶分片 + 超时兜底

---

## 架构概览

```
order → Feign → inventory(19005) → Redis(桶分片+Redis Lua原子)
                                 → MySQL(t_inventory/t_tcc_fence/t_inventory_outbox)
                                 → MQ(INVENTORY_TOPIC → InventoryDeductConsumer)
```

## 端点清单

### 管理员接口 (X-Admin-Call)

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| I01 | POST | `/api/inventory/init` | 初始化库存(Redis桶分片) |
| I05 | POST | `/api/inventory/reinit` | 重建库存(SCAN清桶+MySQL恢复) |
| I07 | POST | `/api/inventory/internal/reconcile` | 对账(Redis vs MySQL) |

### 内部接口 (X-Internal-Call)

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| I02 | POST | `/api/inventory/preDeduct` | 预扣(Lua+Outbox) |
| I03 | POST | `/api/inventory/confirm` | 确认扣减 |
| I04 | POST | `/api/inventory/release` | 释放库存 |
| I08 | POST | `/api/inventory/tcc/try` | **TCC Try(冻结)** |
| I09 | POST | `/api/inventory/tcc/confirm` | **TCC Confirm(确认)** |
| I10 | POST | `/api/inventory/tcc/cancel` | **TCC Cancel(解冻)** |

### 公开接口

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| I06 | GET | `/api/inventory/stock/{skuId}` | 查询库存(CacheAside) |

## Key Redis

| Key | 用途 |
|------|------|
| `inventory:{skuId}:total` | 总库存(Redis权威数据源) |
| `inventory:{skuId}:bucket:{n}` | 分桶库存(Lua原子扣减) |
| `inventory:prededuct:{orderId}` | 预扣记录(30min TTL) |
| `inventory:prededuct:index` | 预扣超时索引 |

## Key MySQL

| 表 | 库 | 说明 |
|------|------|------|
| t_inventory | my_xhs_inventory | available_stock/locked_stock/freezing_stock |
| t_tcc_fence | my_xhs_inventory | TCC防悬挂(xid+branch_id PK, status 1/2/3) |
| t_tcc_freeze_detail | my_xhs_inventory | TCC冻结明细(xid+branch_id+sku_id PK) |
| t_inventory_outbox | my_xhs_inventory | Outbox消息表(uk_order_sku) |
| t_inventory_compensation | my_xhs_inventory | 补偿表(重试超限) |

## Key MQ

| Topic | 用途 |
|------|------|
| INVENTORY_TOPIC:PRE_DEDUCT | 预扣事件 |
| INVENTORY_TOPIC:CONFIRM | 确认扣减→MySQL locked_stock -= qty |
| INVENTORY_TOPIC:RELEASE | 释放→Redis回退 |

## 分布式事务实现

**TCC (I08→I09/I10)**:
- Try: t_tcc_fence防悬挂→freezing_stock冻结
- Confirm: t_tcc_fence状态1→2→confirmFreeze
- Cancel: 空回滚处理→解冻
- 超时兜底: TccTimeoutJob @Scheduled(60s)

**Outbox (I02→MQ)**:
- INSERT t_inventory_outbox→syncSend→markOutboxSent
- 失败cancelOutboxEvent+InventoryOutboxSenderJob兜底

**桶分片 (I01)**:
- Redis Lua原子操作多桶→防热点并发
- CONFIRM/RELEASE使用相同伪订单号幂等
