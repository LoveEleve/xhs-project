# 05 · 对账与 Redis 恢复

> **口径**：先讲**生产环境/行业常见做法**，再对照本项目；代码不代表应然标准。
>
> **一句话**：对账 = **以 Redis 为目标、MySQL 为兜底**；三场景修复 + **水位线**跳过在途 + **防双份全丢** + SCAN 补扫；
> Redis 丢失时由读路径/对账从 MySQL 恢复。
>
> **本篇带走**：① 为什么 Redis 为准 ② 三场景 ③ 水位线 ④ 防双份全丢/SCAN ⑤ 锁/清理/恢复。
> 前置：03。证据：`CartReconcileJob`、`CartService.getCartList/restoreCartFromDb`。

---

## 0. 例子：三种不一致

```
① MQ 丢消息：Redis 有 sku1001:2，MySQL 没有
   → 对账：INSERT MySQL（数量/勾选以 Redis 为准）

② 数量/勾选不一致：Redis qty=3 checked=1，MySQL qty=2 checked=0
   → 对账：UPDATE MySQL

③ 用户删除：Redis 已无 sku1001，MySQL 还有
   → 对账：DELETE MySQL（但 Redis 整键丢失时不能删！）

④ Redis 丢失：items 键没了
   → 读路径：cleared 标记？→ 空车；否则从 MySQL 恢复 + 回写
```

## 1. 生产环境怎么做（淘宝/行业常见）

| 问题 | 常见做法 |
|---|---|
| 以谁为准 | 热数据/实时写侧为权威；DB 为兜底 |
| 对账 | 定时比对 + 差异修复；水位线避开在途 |
| 防双丢 | 检测"缓存全丢" vs "用户清空"，不能盲删 DB |
| 补扫 | 枚举源可能漏（只在 Redis 的用户）→ SCAN 补扫 |
| 锁 | 定时任务/手动端点互斥；锁超时兜底 |
| 清理 | append-only 事件表有保留期 |

## 2. 本项目的实现（代码证据）

### 2.1 三场景：Redis 为目标

```java
List<CartItem> mysqlItems = cartItemMapper.selectList(...);

// 场景 1：Redis 有 + MySQL 无 → INSERT（checked 从 Redis Set 取）
// 场景 2：Redis 有 + MySQL 有 + 数量/勾选不一致 → UPDATE（以 Redis 为准）
// 场景 3：Redis 无 + MySQL 有 → DELETE（用户已删除/MQ 丢消息）
```

- `checked` 状态以 Redis `checked` Set 为准（MySQL 只存 0/1）；
- 脏 key/数量（非数字）跳过，不因一条脏数据中断整用户对账。

### 2.2 水位线：300 秒内写过 → 视为在途

```java
LocalDateTime cutoff = now.minusSeconds(watermarkSeconds); // 默认 300s
boolean inFlight = mysqlItems.stream()
        .anyMatch(i -> i.getUpdatedAt() != null && i.getUpdatedAt().isAfter(cutoff));
if (inFlight) return 0;  // 本轮跳过，下一轮再对
```

- 消息已发、落库未完成 → MySQL 暂时缺行/旧值；直接修会把"正常在途"误判成不一致；
- 水位线用的是 MySQL `updated_at`（= 生产者 eventTime 落库）——多实例时钟倾斜会影响"在途"判断（与 03 同源风险）；
- 代价：在途用户晚一轮（最多 1 小时）收敛。

### 2.3 防双份全丢：`items` 不存在 ≠ 用户清空

```java
boolean keyExists = hasKey(itemsKey);
boolean cleared = hasKey(clearedMarkerKey(userId));
boolean skipDeleteScenario = !keyExists && !cleared;

// items 不存在且无 cleared → 可能是 Redis 故障丢数据
// 此时执行"场景 3 DELETE"会把 MySQL 兜底也删掉 → 双份全丢
if (skipDeleteScenario && !mysqlItems.isEmpty()) {
    log.warn("跳过删除场景(防Redis故障时误删MySQL兜底)");
}
```

- 用户清空 → `cleared` 标记存在 → 场景 3 正常删 MySQL；
- Redis 故障 → 无 `cleared` → 跳过删除，MySQL 陈旧行残留（用户不可见，仅占存储）；
- 读取以 Redis 为准，陈旧行不会被展示。

### 2.4 SCAN 补扫纯 Redis 用户

```java
// 枚举源：MySQL 的 distinct user_id（游标 1000/批）
// 补扫：Redis SCAN myxhs:cart:{*}:items
// 只存在于 Redis 的用户（MQ 丢消息、MySQL 无记录）也能被对账到
```

- 只靠 MySQL 枚举会漏"纯 Redis 用户"；
- SCAN 有固有语义（扩容/迁移窗口可能漏/重），但作为补扫够用。

### 2.5 锁、清理、测试边界

- **锁**：`SETNX lock 600s` + token + Lua 比对释放；定时任务与手动 `reconcileUserWithLock` 共用；
- **超时窗口**：固定 600s，大批量用户超时后可能并发（已知边界）；
- **事件清理**：`t_cart_event` 30 天，单次最多 20×5000 行（有界排空）；
- **无单测**：`CartReconcileJob` 没有专门测试（33 个 cart 测试覆盖 Service/联动/同步/订单消费，不含对账）——已知债。

### 2.6 Redis 恢复：读路径与对账互补

**读路径**（`getCartList`）：

```
items 为空：
  cleared? → 空车
  否则 → restoreCartFromDb（items/checked/sort 回写）→ 重读 Pipeline
```

**对账 Job**：

```
MySQL 有、Redis 有差异 → 修 MySQL（Redis 为准）
Redis 有、MySQL 无 → 补 MySQL
Redis 整键丢失 → 读路径从 MySQL 恢复；对账跳过删除防双丢
```

- 读路径负责"用户访问时"的即时恢复；
- 对账负责"全量/后台"的收敛；
- 两者都以 Redis 为实时权威、MySQL 为兜底。

## 3. 对照表：生产 vs 本项目

| 能力 | 生产环境 | 本项目 | 差异 |
|---|---|---|---|
| 以谁为准 | 实时侧权威 | ✅ Redis | 对齐 |
| 三场景修复 | 常见 | ✅ INSERT/UPDATE/DELETE | 对齐 |
| 水位线 | 常见 | ✅ 300s | 对齐 |
| 防双丢 | 检测缓存全丢 | ✅ items+cleared | 对齐 |
| 补扫 | 枚举+补扫 | ✅ MySQL+SCAN | 对齐 |
| 锁/超时 | 分布式锁 | ⚠️ 600s 固定 | 已知 |
| 对账单测 | 有 | ❌ | 缺口 |

## 4. 十三个坑

1. **把 MySQL 当权威**：会覆盖用户最新操作；Redis 才是实时权威。
2. **没有水位线**：把 MQ 在途误判成差异，删/改正常数据。
3. **`items` 不存在就删 MySQL**：Redis 故障时双份全丢；必须看 `cleared`。
4. **只枚举 MySQL**：纯 Redis 用户漏对账；要 SCAN 补扫。
5. **SCAN 当精确枚举**：扩容/迁移窗口可能漏/重；只做补扫。
6. **无视脏数据**：非数字 key/qty 要跳过，不能中断整用户。
7. **`checked` 不同步**：只比数量不比勾选；场景 2 要同时比。
8. **锁超时**：600s 大批量可能并发；监控/分批。
9. **事件表无限增长**：30 天清理 + 有界排空。
10. **恢复后不重读**：读路径恢复后 checked/sort 仍空（O-P2-7-1）。
11. **对账与在线写竞争**：水位线 + 锁；不要频繁全量。
12. **对账无单测**：当前未覆盖，改动风险高。
13. **水位线依赖生产者时钟**：`updated_at`=eventTime；时钟倾斜会误判在途（与 03 同源）。

## 5. 值不值得深挖 / 一句话

- **值得**：以谁为准、三场景、水位线、防双全丢、SCAN 补扫——缓存/DB 对账的通用套路；
- **一句话带过**：锁 TTL、批次大小；
- **一句话**：**对账 = Redis 为准修 MySQL；水位线避在途，cleared 防双丢，SCAN 补纯 Redis 用户；Redis 丢失读路径恢复。**
- 清车/回补见 **06**；工程债见 **07**。

**面试口径**："购物车对账以 Redis 为准修 MySQL：Redis 有 MySQL 无补录、不一致更新、Redis 无 MySQL 有删除；MySQL 近 300s 有写视为在途跳过；items 键不存在且无 cleared 时跳过删除防 Redis 故障双份全丢；再 SCAN 补扫纯 Redis 用户；读路径 Redis 空时从 MySQL 恢复并重读。"
