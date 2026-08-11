# 07. 库存域（my-xhs-inventory）业务逻辑

> 端口 19010 | 10 端点 | 独立实例（隔离锁竞争）| Redis 权威 + TCC + Outbox

## 一、业务定位
**交易履约的实物约束**：库存水位、预扣/确认/释放、TCC 分布式事务，防止超卖。独立库实例隔离锁竞争。

## 二、库存状态模型
```
total = available_stock(可用) + locked_stock(已锁确认) + freezing_stock(TCC冻结)
```

## 三、核心业务逻辑 / 不变量
1. **预扣→确认→释放**：
   - 预扣 I02：Redis Lua 原子多桶递减 + 写预扣记录(TTL 30min) + Outbox → MQ。
   - 确认 I03：locked += qty, available -= qty。
   - 释放 I04：available += qty。
   - 超时释放：PreDeductTimeoutJob 30min 未确认/释放自动释放。
2. **TCC + Fence 防悬挂**：Try 插 status=1；迟到的 Cancel 插 3=SUSPENDED 跳过；Confirm 1→2。
3. **空回滚**：Cancel 无 Try → SKIP；随后 Try 遇已有 Cancel → SUSPENDED。
4. **桶分片**：`inventory:{skuId}:bucket:{0..N}` + total，Redis Lua 原子多桶扣减。
5. **查询 CacheAside**：Redis total → 未命中查 MySQL 回填。

## 四、异常路径
- 预扣不足 → 库存不足拒绝下单。
- TCC 悬挂/空回滚 → Fence 表 + 状态机保护。
- 超时预扣未确认 → Job 自动释放（防库存泄漏）。

## 五、对 AI 项目（指标/诊断）的价值
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 预扣成功率 | t_inventory_outbox + 日志 | ⚠️ 需统计 |
| 库存水位/冻结占比 | t_inventory | ✅ |
| 库存不足 SKU | t_inventory | ✅ |
| TCC 悬挂/异常事务 | t_tcc_fence | ✅ |
| 超时释放泄漏 | PreDeduct Job 日志 | ⚠️ 需观测 |

> **PLAN 场景 C 所在地**，TCC/Fence 是很好的诊断样本；数据就绪度较好（有 t_inventory/t_tcc_fence 明确结构）。
