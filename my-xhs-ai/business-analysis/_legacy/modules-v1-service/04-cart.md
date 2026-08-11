# 04. 购物车域（my-xhs-cart）业务逻辑

> 端口 19008 | 10 端点 | Redis 三结构 + MQ 异步持久化 + Feign

## 一、业务定位
**电商转化漏斗的中间环节**：把"浏览商品"推进到"准备下单"，是转化率的关键节点。Redis 为权威数据源，MQ 异步落库。

## 二、核心数据模型（Redis 三结构，`{userId}` hash tag 保证同 slot 可 Lua 原子）
```
myxhs:cart:{userId}:items    Hash  skuId→quantity
myxhs:cart:{userId}:checked  Set   已勾选skuId
myxhs:cart:{userId}:sort     ZSet  skuId→addedAt(倒序)
```

## 三、关键业务规则 / 不变量
1. **限制**：上限 50 种商品；单 skuId 最多 99 件（HINCRBY 截断）。
2. **默认勾选**：加购即勾选（符合绝大多数场景）。
3. **匿名合并**：登录时合并取 `max(quantity)`（幂等）；数量 ≥10000 发 UPDATE 事件。
4. **勾选防 TOCTOU**：单条勾选先 HEXISTS 验证存在再 SADD/SREM。
5. **商品信息刷新**：Feign 批量取 SKU；下架/失败 → `valid=false`（前端灰色），不计入 checkedAmount，不阻塞返回。
6. **持久化**：CartSyncConsumer 按 `uk_user_sku` UPSERT 到 t_cart_item（MQ 幂等）。

## 四、异常路径
- 下架商品仍在购物车 → 标记无效但保留展示。
- MQ 落库延迟 → MySQL 与 Redis 可能短暂不一致 → 对账接口 C10 手动修复。

## 五、对 AI 项目（指标/诊断）的价值
| 指标 | 来源 | 数据就绪度 |
|------|------|-----------|
| 加购转化率（浏览→加购） | t_user_behavior + cart | ⚠️ 需漏斗关键环节 |
| 购物车放弃率（加购→下单） | cart → order | ⚠️ 需关联漏斗 |
| 加购商品 TOP、失效商品占比 | cart Redis/MySQL | ⚠️ Redis 权威，非持久化 |
| 购物车数量分布 | t_cart_item | ✅ |

> **转化漏斗诊断的关键一环**。PLAN 场景 A"订单下降"要归因到漏斗断点，购物车数据不可或缺——但当前购物车权威在 Redis，历史漏斗分析受限。
