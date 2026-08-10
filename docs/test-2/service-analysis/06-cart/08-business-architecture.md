# 购物车业务架构与分布式一致性

> 综合：所有已完成的深度文档，补充业务视角

---

## 1. 购物车在电商链路中的位置

```
用户浏览（content/analytics）
  ↓ 查看商品详情
用户加购（cart）← 本模块
  ↓ 去购物车页，勾选商品
用户下单（order）
  ↓ 锁定库存、生成订单
用户支付（payment）
  ↓ 扣款
用户收货（order + notification）
```

购物车是"浏览→加购→下单"转换漏斗的关键节点。它的职责边界：
- **上游依赖**：product（商品详情）、用户登录态
- **下游消费者**：order（结算时传递勾选的 SKU 列表，下单后清空已购商品）

**退出重开的恢复路径**：购物车数据完全在 Redis 中，用户退出后重新登录 → `GET /api/cart/list` → Pipeline 三结构读取 → 购物车原样恢复（数量、勾选状态、排序位置不变）。只要 Redis 不丢数据，购物车永远存在。

---

## 2. 数据流：从加购到下单的完整链路

```
用户操作：
  ① 浏览商详 → 加购 skuId=A, qty=3  → Redis Lua: items/checked/sort
  ② 修改数量 → skuId=A, qty=2       → Redis HSET
  ③ 勾选     → skuId=A, checked=true → Redis SADD
  ④ 查看购物车列表                    → Redis Pipeline + Feign product

用户点结算：
  → 前端将勾选的 skuIds 传给 order 服务
  → order 根据 skuIds 创建订单
  → order 可选：调用 cart 清空已下单的商品
```

**cart 和 order 的数据衔接是隐式的**：cart 不主动推送给 order——前端负责传递勾选的 skuIds。cart 只管"用户想买什么"，order 负责"把想买的变成订单"。这是合理的职责分离——cart 不需要知道 order 的存在。

**下单后购物车的清理**：当前 `CLEAR` 操作只支持全量清空。下单后应该只清空已购商品（`checkout` 后批量删除勾选的 SKU），而不是清空整个购物车——这个语义在 `CartController` 中尚未实现。

---

## 3. Redis 故障恢复路径

### 场景 1：Redis 完全不可用（运行中）

```
用户 GET /api/cart/list → Pipeline 抛 RedisConnectionException
→ RuntimeException → GlobalExceptionHandler → 500

用户 POST /api/cart/add → Lua 执行抛异常 → 500
```

**结论**：Redis 不可用时购物车完全不可用——没有降级到 MySQL 读。cart 不是 product 的缓存层（product 缓存丢失可以从 DB 重建），cart 的 Redis 是数据源。

### 场景 2：Redis 数据丢失（重启/RDB 恢复异常）

```
Redis 重启 → RDB 回退到 2 小时前的快照
→ 2 小时内加入的购物车商品全部丢失
→ MySQL 中仍有最新数据（MQ 异步持久化）
```

**恢复路径**：`CartReconcileJob` 当前只做 Redis→MySQL 方向修复——以 Redis 为准更新 MySQL。**没有 MySQL→Redis 的恢复功能**。

**修复方案**：扩展 `CartReconcileJob.reconcileUser()`：

```java
// 新增：Redis 数据丢失检测
if (redisItems.isEmpty() && !mysqlItems.isEmpty()) {
    // MySQL 有数据，Redis 为空 → 恢复 Redis
    for (CartItem item : mysqlItems) {
        HSET itemsKey item.skuId item.quantity
        if (item.checked) SADD checkedKey item.skuId
        ZADD sortKey timestamp item.skuId  // 使用 MySQL 的 created_at
    }
}
```

当前未实现，已知设计空白。

### 场景 3：MQ 持久化全部丢失

MQ 消息发送失败全部丢弃 → MySQL 中没有购物车数据 → Redis 数据完好。

**影响**：Redis 可用时用户不受影响。Redis 也丢失时（场景 2），MySQL 没有兜底数据。`CartReconcileJob` 会看到 MySQL 为空但 Redis 有数据 → 执行 INSERT 将 Redis 数据写回 MySQL。

**结论**：三层恢复中，Redis 正常 + MySQL 异常 → 对账修复 INSERT；Redis 异常 + MySQL 正常 → 当前未实现恢复；两者都异常 → 永久丢失。

---

## 4. 多实例一致性分析

```
实例 A: 用户 10001 在 A 上加购 skuId=1
实例 B: 用户 10001 在 B 上查看购物车 → Pipeline 三结构 → 能看到 skuId=1？

→ 能！因为 Redis 是共享的（Sentinel 模式单主）
```

cart 的所有读写都经过 Redis——没有本地缓存（Caffeine）或实例间的不一致窗口。多实例天然一致的原因是：

| 组件 | 是否共享 | 一致性 |
|------|:---:|------|
| Redis（购物车数据） | ✅ 所有实例连同一 Sentinel Master | 强一致 |
| Feign（product 详情） | ✅ Nacos 发现多实例，随机路由 | 允许不一致（不同实例看到不同 product 实例的返回） |
| MQ（持久化） | ✅ 共享 Broker | 消费进度按 consumerGroup 管理 |

Feign 调 product 可能返回不同的响应（如果 product 也部署多实例，且某个实例缓存了旧数据），但购物车核心数据（三结构）永远一致。

---

## 5. 与其他业务模块的交互矩阵

| 交互方向 | 方式 | 触发时机 | 数据流 |
|------|:---:|------|------|
| product → cart | Feign（cart 调用 product） | getCartList 获取 SKU 详情 | skuIds → SkuDTO(name,price,stock,status) |
| user → cart | HTTP Header `X-User-Id` | 所有操作（Gateway 注入） | userId 标识购物车归属 |
| cart → order | 前端传递（隐式） | 用户点结算 | 前端将勾选的 skuIds 传给 order API |
| cart → MySQL | MQ 异步 | 所有写操作后 | CartSyncEvent → CartSyncConsumer → INSERT/UPDATE/DELETE |
| cart → XXL-Job | 定时调度 | 凌晨 4 点 | CartReconcileJob 逐用户对账 |

**cart 不主动调用 order**：结算由前端触发，order 是请求的接收方。cart 只提供购物车数据，不关心谁消费这些数据。

---

## 6. 发散：与主流电商购物车架构对比

| 电商 | 存储 | 持久化 | 匿名购物车 |
|------|------|------|------|
| **my-xhs (当前)** | Redis 三结构（Hash+Set+ZSet） | MQ 异步 MySQL + 对账 | 取 max 合并 |
| 京东 | Redis + MySQL 双写 | 同步双写 | 覆盖合并 |
| 淘宝 | Tair（阿里自研 KV） + MySQL | 异步落库 | 取 max 合并 |
| Amazon | DynamoDB | 原生持久化 | 合并后提示选择 |

my-xhs 的方案最接近淘宝——Redis 作为热数据层，MySQL 异步持久化做备份。差异在于淘宝有 Tair 的自动容灾切换，my-xhs 依赖 Sentinel 手动切换 + XXL-Job 对账修复。

---

## 关联文档

- `01-cart-module.md` — 基础架构
- `03-redis-structure.md` — 为什么是三结构
- `06-mq-persistence.md` — Redis→MySQL 持久化
- `../04-counter/07-data-consistency.md` — counter 三层一致性的对比参考
