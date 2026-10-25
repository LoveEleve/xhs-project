# 02 · 写路径与 Lua 原子化

> **口径**：先讲**生产环境/行业常见做法**，再对照本项目；代码不代表应然标准。
>
> **一句话**：购物车写路径 = **8 个 Lua 把"多结构变更"做成原子**（加购/改量/删除/批量删/勾选/全选/清空/合并），
> 上限、防幽灵、防复活、清空标记都在 Redis 单脚本完成。
>
> **本篇带走**：① 8 个脚本语义/返回 ② 原子性/上限/防幽灵/防复活 ③ 事件发送 ④ TTL 与已知债。
> 前置：01。证据：`lua/*.lua`、`CartService`、`RedisScriptConfig`。

---

## 0. 例子：三个并发场景

```
① 并发加购同一 SKU（库存 100，两请求各 +1）
   非原子：HEXISTS/HLEN/HINCRBY/SADD/ZADD 之间插队 → 超上限/勾选幽灵
   Lua：5 步原子 → 结果确定

② 删除后立刻改量
   非原子：hasKey 通过 → 并发删除 → HSET → 已删商品"复活"
   Lua：HEXISTS + HSET 原子 → 删除赢则返回 0（不复活）

③ 清空的同时加购
   非原子：DEL 三结构/写 marker 之间加购写回 → 被覆盖
   Lua：三键 DEL + marker 一次原子 → 结果确定
```

## 1. 生产环境怎么做（淘宝/行业常见）

| 问题 | 常见做法 |
|---|---|
| 多结构一致 | Lua/事务把"Hash+Set+ZSet"变更原子化 |
| 并发上限 | 脚本内检查，避免先查后写 |
| 防幽灵 | 写前校验存在性（商品/车项） |
| 清空/合并 | 单脚本原子；标记位区分"空"与"丢失" |
| 落库 | 异步事件 + 对账，不阻塞写 |

## 2. 本项目的实现（代码证据）

### 2.1 8 个 Lua 脚本

| 脚本 | 操作结构 | 语义 | 返回 |
|---|---|---|---|
| `cart_add` | items/checked/sort | 上限检查 + HINCRBY + 截断 99 + 新项 SADD/ZADD + TTL | `-1` 满；新项 `qty+10000`；旧项 `qty` |
| `cart_update_quantity` | items | `HEXISTS` + `HSET`（防复活） | 1/0 |
| `cart_remove` | 三结构 | 原子删三结构 | 1/0 |
| `cart_remove_batch` | 三结构 | 按 sku 列表批量删（C-006 清车） | 删除数 |
| `cart_check_item` | items/checked | `HEXISTS` + `SADD/SREM`（防幽灵勾选） | 1/0 |
| `cart_check_all` | items/checked | `HKEYS` → `DEL` → `SADD`（原子全选；取消=DEL O(1)） | 选中数/0 |
| `cart_clear` | 三结构+marker | `DEL` 三键 + `SET cleared`（同脚本） | 1 |
| `cart_merge_item` | 三结构 | 已有 `max(qty)`；新项上限+SADD/ZADD+TTL | 新项 qty；旧项 `merged+10000`；`0` 满 |

> 模块文档曾说"7 个脚本"——C-006 新增 `cart_remove_batch` 后实际是 **8 个**。

### 2.2 关键脚本：`cart_add`

```lua
-- 1) 新商品才检查品种上限
if redis.call('HEXISTS', itemsKey, skuId) == 0 then
    if redis.call('HLEN', itemsKey) >= maxCartSize then return -1 end
end

-- 2) HINCRBY 累加 + 单品上限截断
local newQuantity = redis.call('HINCRBY', itemsKey, skuId, quantity)
if newQuantity > maxItemQuantity then
    redis.call('HSET', itemsKey, skuId, maxItemQuantity)
    newQuantity = maxItemQuantity
end

-- 3) 新商品才默认选中 + 记录加购时间（NX）
if exists == 0 then
    redis.call('SADD', checkedKey, skuId)
    redis.call('ZADD', sortKey, 'NX', timestamp, skuId)
end

-- 4) 脚本内滑动 TTL（add/merge 双保险）
redis.call('EXPIRE', itemsKey, ttl)
redis.call('EXPIRE', checkedKey, ttl)
redis.call('EXPIRE', sortKey, ttl)

return exists == 0 and (newQuantity + 10000) or newQuantity
```

**`+10000` 标志（T-107）**：调用方据此区分"新商品（发 ADD checked=1）"与"已存在商品（发 UPDATE checked=null，不覆盖 MySQL 勾选）"。

### 2.3 合并：`cart_merge_item`

```lua
if exists == 1 then
    local merged = math.max(currentQty, ARGV[2])   -- max，不覆盖用户调小的数量
    if merged > maxQty then merged = maxQty end
    HSET(items, skuId, merged)
    -- 不触碰 checked：保持用户原勾选
    return merged + 10000
else
    if HLEN(items) >= MAX_CART_SIZE then return 0 end  -- 满（注释写 -1，代码是 0）
    qty = min(ARGV[2], maxQty)
    HSET(items, skuId, qty)
    SADD(checked, skuId)
    ZADD(sort, NX, now, skuId)
    return qty
end
```

- **max 语义**：用户把数量调小后，重复回补不会抬回（配合 restored 去重，06）；
- **已有项不改 checked**：保留用户勾选状态；
- 满返回 **0**（注释说 `-1`，代码为准——文档漂移）。

### 2.4 清空：三键 + 标记一次原子

```lua
-- KEYS: items/checked/sort/clearedMarker
redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
redis.call('SET', KEYS[4], '1', 'EX', markerTtl)
return 1
```

- 避免"删了一半又加购写回"；
- `cleared` 标记让读路径/对账区分"用户清空"与"Redis 丢失"（01）。

### 2.5 TTL：脚本内 + Service 统一刷新

- `cart_add`/`cart_merge_item` 在脚本内刷新三键 TTL；
- 其他写（改量/删除/勾选/全选）**不在脚本内**刷新，由 `CartService.refreshTTL`（三键原子 EXPIRE 的 Lua）统一续期；
- `clear` 删键 + marker TTL；
- 维护时**两处都要看**：只改脚本或只改 Service 都会漏。

### 2.6 事件发送：MQ 失败不影响购物车

```java
// Lua 成功后：发顺序消息（userId 分区）
rocketMQTemplate.asyncSendOrderly(
        CART_TOPIC + ":" + action,
        message, String.valueOf(userId), callback);
```

- Redis 是权威；MQ 只是异步落库通道；
- 发送失败只记日志，**不影响用户操作**；对账修复（05）；
- `checked` 事件值：ADD 新项=1、旧项=null；UPDATE=null；DELETE=0；CHECK/CHECK_ALL 按操作。

## 3. 对照表：生产 vs 本项目

| 能力 | 生产环境 | 本项目 | 差异 |
|---|---|---|---|
| 多结构原子 | Lua/事务 | ✅ 8 脚本 | 对齐 |
| 上限/截断 | 脚本内 | ✅ 50 种/单品 99 | 对齐 |
| 防幽灵/防复活 | HEXISTS 校验 | ✅ | 对齐 |
| 清空标记 | 有 | ✅ cleared | 对齐 |
| 落库 | 异步事件 | ✅ 顺序 MQ | 对齐 |
| TTL | 滑动 | ✅ refreshTTL + 脚本双保险 | 对齐 |
| 批量合并 | 可选 | ❌ 逐项 | 简化 |

## 4. 十二个坑

1. **多结构非原子**：items 删了 checked/sort 没删 → 幽灵勾选/排序。
2. **改量不校验存在**：并发删除后 HSET 复活；`HEXISTS` 必须进 Lua。
3. **勾选不校验存在**：SADD 创建幽灵勾选；同样 HEXISTS。
4. **清空分两步**：DEL 与 marker 之间加购写回被覆盖；单脚本。
5. **已有商品加购改勾选**：T-107 的 `+10000` 标志就是修这个。
6. **合并覆盖用户调小**：必须 `max`，不是直接写回补数量。
7. **上限只查新增**：HINCRBY 可能超单品上限；脚本内截断 99。
8. **合并满返回码**：注释 `-1`、代码 `0`；以代码为准（文档漂移）。
9. **TTL 只改脚本**：其他写靠 `refreshTTL`；两处都要维护。
10. **MQ 失败当写失败**：Redis 已是权威；MQ 失败只影响落库，不影响用户。
11. **Lua key 跨 slot**：三键必须 `{userId}` 同 slot。
12. **`cart_remove_batch` 不刷新 TTL**：Service 会 refreshTTL；别只看脚本。

## 5. 值不值得深挖 / 一句话

- **值得**：8 脚本的原子边界、上限/标志位/防幽灵/防复活、清空标记——购物车写路径核心；
- **一句话带过**：脚本参数、TTL 数值、返回码；
- **一句话**：**购物车写 = 8 个 Lua 原子化多结构变更；上限/勾选/清空/合并都在 Redis 单脚本完成，MQ 只负责异步落库。**
- 同步链路见 **03**；清车/回补见 **06**。

**面试口径**："购物车写路径 8 个 Lua：add 做上限+累加+截断+新项勾选/排序+TTL，返回 +10000 区分新旧；update/check 都先 HEXISTS 防复活/幽灵；clear 三键 DEL + cleared 标记原子；merge 用 max 保留用户调小；MQ 失败不影响 Redis 权威，靠对账收敛。"
