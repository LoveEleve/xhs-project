# 库存与履约（TCC / 水位 / 防超卖）

## 业务问题（AI 能回答）
- 库存水位、可售/锁定/冻结占比。
- 预扣成功率、库存不足 SKU、TCC 异常（悬挂/空回滚）。
- 库存预扣失败诊断（旧 PLAN 场景 C）——**就绪度较好**。

## 口径与定义
- **库存状态**：`total = available(可售) + locked(已锁确认) + freezing(TCC冻结)`。
- **预扣→确认→释放**：Redis Lua 原子多桶递减 → 写预扣记录(TTL 30min) → Outbox → MQ；确认 locked+=qty/available-=qty；释放 available+=qty；超时 Job 自动释放。
- **TCC + Fence 防悬挂**：Try 插 status=1；迟到 Cancel 插 3=SUSPENDED 跳过；Confirm 1→2；空回滚 SKIP。

## 数据来源与就绪度
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 库存水位/冻结占比 | t_inventory | ✅ |
| 库存不足 SKU | t_inventory | ✅ |
| 预扣成功率 | 预扣记录/日志 | ⚠️ 需统计 |
| TCC 悬挂/异常事务 | t_tcc_fence | ✅ |
| 超时释放泄漏 | 超时 Job 日志 | ⚠️ 需观测 |

## 关键不变量 / 可信边界
- Redis 权威数据源（桶分片 Lua 原子扣减），独立库实例隔离锁竞争。
- Fence 表保证 TCC 最终一致性（防悬挂/空回滚）。
- 预扣 30min 未确认自动释放，防库存泄漏。

## 关联诊断
- "预扣失败率异常" → 区分：库存不足 vs 并发冲突 vs TCC 异常 vs 服务错误；关联订单流量 + Prometheus。
- "库存不准/超卖" → 对账（t_inventory vs Redis）+ TCC fence 排查。
