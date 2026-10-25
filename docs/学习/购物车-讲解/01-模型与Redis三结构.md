# 01 · 模型与 Redis 三结构

> **口径**：先讲**生产环境/行业常见做法**，再对照本项目；代码不代表应然标准。
>
> **一句话**：购物车 = **用户维度的购买意图暂存区**；本项目用 **Redis 三结构（items/checked/sort）+ cleared 标记**
> 做权威，MySQL 兜底 + 事件流水，对账收敛。
>
> **本篇带走**：① 表/实体 ② 三结构/同 slot/TTL ③ 为什么 Redis 权威 ④ 与 MySQL 的关系与缺口。
> 前置：00。证据：`CartService`、`CartItem`/`CartEvent`、`02-购物车.md` §3。

---

## 0. 例子：加购/改量/勾选/清空

```
加购 SKU 1001 × 2
  items  H{1001:2}
  checked S{1001}
  sort   Z{1001: now}

改量 3        → items H{1001:3}
取消勾选      → checked 移除 1001
清空          → DEL items/checked/sort + SET cleared:userId
每次写        → 三键滑动 TTL 30 天
```

## 1. 生产环境怎么做（淘宝/行业常见）

| 维度 | 常见做法 |
|---|---|
| 模型 | 用户 × SKU × 数量 × 勾选 × 加购时间；匿名车/多端合并 |
| 展示 | 价格/库存/优惠/失效标记（结算前试算） |
| 存储 | Redis 热数据 + DB 持久化；或 DB 权威 + 缓存 |
| 一致性 | 异步落库 + 对账；Redis 丢失可恢复 |
| TTL | 长期未活跃清理（如 30/90 天） |
| 边界 | 购物车只存意图；价格/库存在下单时校验 |

## 2. 本项目的实现（代码证据）

### 2.1 表模型

**`t_cart_item`**（用户车项，UPSERT 幂等）：

```java
public class CartItem {          // 不继承 BaseEntity（无 deleted 逻辑删）
    private Long id;
    private Long userId;
    private Long skuId;
    private Integer quantity;
    private Integer checked;    // 0/1
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- `UNIQUE uk_user_sku(user_id, sku_id)`：同一 SKU 只有一行，UPSERT 基础；
- `updated_at DATETIME(3)`：毫秒精度，供同步链路时间戳 CAS（03）。

**`t_cart_event`**（append-only 事件流水，30 天）：

```java
public class CartEvent {
    private Long userId;
    private Long skuId;
    private String action;      // ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR
    private Integer quantity;
    private Integer checked;
    private LocalDateTime eventTime;  // 生产者时钟
    private String msgId;             // MQ 幂等
}
```

- `UNIQUE uk_msg_id`：消费幂等；
- `CLEAR` 用 `sku_id=0` 哨兵；
- 保留 30 天（有界清理），供漏斗/分析。

### 2.2 Redis 三结构 + cleared

```java
private static final String KEY_PREFIX = "myxhs:cart:{";  // {userId} hash tag
private static String itemsKey(Long userId)   { return KEY_PREFIX + userId + "}:items"; }
private static String checkedKey(Long userId) { return KEY_PREFIX + userId + "}:checked"; }
private static String sortKey(Long userId)    { return KEY_PREFIX + userId + "}:sort"; }
private static String clearedMarkerKey(Long userId) { return KEY_PREFIX + userId + "}:cleared"; }

private static final long CART_TTL_DAYS = 30;  // 滑动 TTL
```

| Key | 结构 | 语义 |
|---|---|---|
| `items` | Hash | `skuId → quantity`（车项与数量） |
| `checked` | Set | 勾选的 skuId（全选/结算） |
| `sort` | ZSet | `skuId → 加购时间戳`（倒序展示） |
| `cleared` | String | 清空标记（区分"空车" vs "Redis 丢了"） |

- **`{userId}` 同 slot**：同一用户三键在 Cluster 同 slot，Lua 多 key 不 CROSSSLOT；
- **30 天 TTL**：每次写后由 `refreshTTL`（三键原子 EXPIRE 的 Lua）刷新；`cart_add`/`cart_merge_item` 还在各自脚本内刷新（双保险）；`clear` 删键 + 写 cleared 标记；
- **cleared 标记**：清空时原子写入（TTL 30 天），对账/读路径据此判断"真空车"还是"Redis 数据丢失"。

### 2.3 为什么 Redis 是权威

- 购物车是**高频写 + 读**（加购/改量/勾选/全选），Redis 原地原子操作最合适；
- 8 个 Lua 把"多结构变更"做成原子（02 篇）；
- MySQL 是**异步兜底**：顺序消息落库（03）、对账修正（05）、Redis 丢失恢复（05）；
- 代价：Redis 故障时列表可能降级/从 MySQL 恢复；不是强一致，是最终一致。

### 2.4 边界与缺口（诚实）

| 能力 | 本项目 | 影响 |
|---|---|---|
| 库存可见 | ❌ 列表不返回库存 | 真实校验在下单 |
| 优惠试算 | ❌ 只有 `Σ 单价×数量` | 结算价在 order |
| 匿名车 | ⚠️ 客户端带入 | 换设备丢车/可伪造 |
| 失效项 | ⚠️ 只标 `valid/invalidReason` | 不自动清理 |
| 多端/多车 | ❌ 单用户单车 | 简化 |
| 退款回补 | ❌ | 非标准行为 |
| 价格/商品快照 | ❌ 实时查 SKU | 下单用订单快照 |

## 3. 对照表：生产 vs 本项目

| 能力 | 生产环境 | 本项目 | 差异 |
|---|---|---|---|
| 用户×SKU×数量×勾选 | 标准 | ✅ | 对齐 |
| 存储 | Redis+DB | ✅ Redis 权威 + MySQL 兜底 | 对齐 |
| 多端/匿名合并 | 服务端匿名车 | ⚠️ 客户端带入 | 简化 |
| 价格/库存/优惠 | 展示+试算 | ❌ | 缺口 |
| TTL | 长期清理 | ✅ 30 天滑动 | 对齐 |
| 失效项 | 失效区/自动清理 | ⚠️ 标记不清理 | 简化 |
| 一致性 | 异步+对账 | ✅ | 对齐 |

## 4. 十个坑

1. **Key 不加 hash tag**：三键跨 slot，Lua CROSSSLOT；必须 `{userId}`。
2. **三结构不同步**：items 有、checked/sort 没有 → 列表/全选错乱；涉及增删/清空的写必须原子改对应结构（改量只 items、勾选只 checked，不是所有写都改三结构）。
3. **只改 Lua 忘了 Service 刷新**：写脚本只有 add/merge 内刷新；其余写靠 `CartService.refreshTTL` 统一续期——两处都要维护。
4. **cleared 标记语义**：没有它，分不清"用户清空"与"Redis 丢失"；对账会误删/误恢复。
5. **UK 不建/不捕获 DuplicateKey**：并发 UPSERT 冲突；`uk_user_sku` + 捕获转 UPDATE。
6. **事件流水不幂等**：`uk_msg_id` 兜底；CLEAR 哨兵 sku_id=0。
7. **把 MySQL 当权威**：高频写会打 DB；本项目 Redis 权威、MySQL 兜底。
8. **匿名单直接伪造**：客户端带入 items，服务端只做上限/merge；安全边界有限。
9. **库存/价格放购物车**：高频变化且易不一致；下单时校验/快照。
10. **上限不设**：50 种/单品 99；Lua 内检查，避免超限。

## 5. 值不值得深挖 / 一句话

- **值得**：三结构设计、同 slot、TTL/cleared、Redis 权威 vs MySQL 兜底——购物车模型的核心；
- **一句话带过**：字段名、TTL 天数；
- **一句话**：**购物车 = 用户×SKU×数量×勾选；Redis 三结构 + cleared 权威，MySQL 兜底 + 事件流水，对账收敛；TTL 每次写后由 refreshTTL 续期。**
- 写路径见 **02**；同步见 **03**；对账见 **05**。

**面试口径**："购物车按用户维度存 SKU/数量/勾选/加购时间；本项目用 Redis Hash(items)+Set(checked)+ZSet(sort)，`{userId}` 同 slot、30 天滑动 TTL，清空有 cleared 标记；MySQL 只做异步兜底和事件流水，Redis 是权威，靠对账收敛。"
