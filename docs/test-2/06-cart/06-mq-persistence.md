# MQ 异步持久化与对账修复

> 源码：`CartSyncConsumer.java` + `CartReconcileJob.java` + `CartService.sendCartSyncEvent()`
> 验证：`02-cart-test.md` §1.1

---

## 1. 写链路：Redis 权威 + MQ 异步持久化

```
addToCart/updateQuantity/removeFromCart/checkItem:
  ① Redis 写入（Lua 原子 / 单命令） → 立即返回 200
  ② MQ asyncSend CartSyncEvent → CartSyncConsumer → MySQL
```

用户操作的响应时间只取决于 Redis 写入延迟（~1ms）。MQ 发送是异步的——失败不会阻塞用户。

```java
// CartService.sendCartSyncEvent():
rocketMQTemplate.asyncSend(CART_TOPIC + ":" + action, message, new SendCallback() {
    public void onSuccess(SendResult result) { log.debug("MQ发送成功"); }
    public void onException(Throwable e) { log.error("MQ发送失败(不影响购物车操作)"); }
});
```

**设计决策**：MQ 发送失败不影响购物车操作——用户已看到 200，Redis 数据正确。丢失的持久化由对账修复补回。

---

## 2. CartSyncConsumer：UPSERT 幂等持久化

```java
@RocketMQMessageListener(
    topic = "CART_TOPIC",
    consumerGroup = "cart-sync-consumer-group",
    selectorExpression = "*",
    maxReconsumeTimes = 3
)
```

**四种操作的 MySQL 映射**：

| MQ Action | MySQL 操作 | 幂等策略 |
|------|------|------|
| ADD / UPDATE | INSERT → DuplicateKeyException → SELECT + UPDATE | `uk_user_sku` 保证重复 INSERT 不产生重复行 |
| DELETE | `DELETE FROM t_cart_item WHERE user_id=? AND sku_id=?` | 物理删除，重复执行无影响 |
| CHECK | SELECT → UPDATE checked | 覆盖写入，幂等 |

**UPSERT 为什么不直接用 SQL？** counter 模块的 `insert ... ON DUPLICATE KEY UPDATE` 是一次 SQL 完成。cart 用了 `try INSERT → catch DuplicateKeyException → SELECT + UPDATE` 三步。区别：

| 方案 | 优点 | 缺点 |
|------|------|------|
| `ON DUPLICATE KEY UPDATE` (counter) | 1 次 SQL，性能高 | 硬编码 SQL，MyBatis-Plus 不原生支持 |
| `try-catch DuplicateKeyException` (cart) | 代码简单，MyBatis-Plus 友好 | 异常时 3 次 SQL，但购物车 QPS 低可接受 |

**为什么购物车用物理删除而不是逻辑删除？** CartItem 不继承 BaseEntity——`deleteCartItem` 执行 `cartItemMapper.delete(wrapper)` 生成 `DELETE FROM t_cart_item WHERE ...`。购物车商品的生命周期简单：用户删除 = 永久消失，不需要软删除的"恢复"语义。

---

## 3. CartReconcileJob：三方对账

```java
@XxlJob("cartReconcileJob")  // Cron: 0 0 4 * * ?（每天凌晨 4 点）
public void reconcile() {
    List<Long> userIds = cartItemMapper.selectDistinctUserIds();  // 获取所有有购物车的用户
    for (Long userId : userIds) {
        reconcileUser(userId);  // 逐用户对账
    }
}
```

**修复策略（Redis 为权威源）**：

```java
reconcileUser(userId):
  // 场景 1：Redis 有 + MySQL 无 → INSERT MySQL
  for each redis item:
    if not in mysql → cartItemMapper.insert(CartItem{...})

  // 场景 2：Redis 有 + MySQL 有 + 不一致 → UPDATE MySQL
  for each mysql item:
    if in redis && (qty || checked differs) → cartItemMapper.updateById(...)

  // 场景 3：Redis 无 + MySQL 有 → DELETE MySQL
  for each mysql item:
    if not in redis → cartItemMapper.deleteById(...)
```

**与 counter 对账的差异**：

| 维度 | counter reconcile | cart reconcile |
|------|------|------|
| 扫描方式 | 游标分页 `SELECT * WHERE id > lastId LIMIT 1000` | 先查不重复 userId → 逐用户对账 |
| 对账维度 | 全表所有计数器 | 逐用户购物车 |
| 修复方向 | 双向（通常 Redis→DB，Redis=0 时 DB→Redis） | Redis→DB（Redis 永远是权威源） |
| Pipeline 优化 | multiGet 批量 | HGETALL + SMEMBERS 一次调用 |
| 调度时间 | 凌晨 3 点 | 凌晨 4 点 |

cart 的对账更简单——永远以 Redis 为准。counter 需要双向修复因为 Redis 可能丢失数据（对账在 counter 中也负责 Redis 恢复）。

**为什么按用户对账而不是按行？**购物车数据是按用户组织的——一个用户的购物车必须是一致的（Hash + Set 的状态不能分裂）。counter 的计数器是独立行——每个 Key 单独修复不影响其他 Key。cart 的对账从用户维度出发更安全。

---

## 4. 与 counter 模块 MQ 持久化对比

| 维度 | cart | counter |
|------|------|------|
| MQ 角色 | 从 Redis 异步写 MySQL（持久化通道） | 从 analytics 接收事件更新 Redis（业务通道） |
| Redis 角色 | 权威数据源 | 权威数据源 |
| Consumer UPSERT | try-catch DuplicateKeyException | `INSERT ON DUPLICATE KEY UPDATE` |
| 对账 | 逐用户三方对账（凌晨 4 点） | 游标全表对账（凌晨 3 点） |
| DLQ | `cart-sync-consumer-group` | `counter-consumer-group` |
| 幂等 | uk_user_sku 防重复 + 物理删除幂等 | msgId Lua 去重 |

两个模块都用相同的 MQ 异步持久化模式——Redis 先写 → 返回 → MQ 后写 MySQL。区别在于 counter 的 MQ 是入口（外部事件驱动），cart 的 MQ 是出口（内部持久化通道）。

---

## 关联文档

- `01-cart-module.md` — §6 MQ 异步持久化
- `02-cart-test.md` — §1.1 MQ 同步验证
- `04-lua-scripts.md` — Lua（MQ 持久化的前置步骤）
