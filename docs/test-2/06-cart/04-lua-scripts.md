# Lua 脚本原子性详解

> 源码：`cart_add.lua` + `cart_remove.lua` + `cart_check_all.lua`（3 文件）
> 注册：`RedisScriptConfig.java`（3 个 Bean）
> 验证：`02-cart-test.md` §1.1 / §1.5 / §1.7

---

## 1. 为什么购物车需要 Lua 脚本？

购物车操作涉及 Redis 的三个独立结构（Hash/Set/ZSet）。分开执行每一条命令会产生中间状态：

```
addToCart 需要 5 个命令：
  HEXISTS(skuId) → HLEN ≤ 50 → HINCRBY + 截断 → SADD → ZADD

removeFromCart 需要 3 个命令：
  HDEL + SREM + ZREM

checkAll 需要 N+2 个命令：
  HKEYS → DEL → SADD × N
```

如果每个命令单独发送——中间状态对其他线程可见——幽灵商品、超上限、选中状态丢失等问题。

Lua 脚本在 Redis 单线程中执行——所有命令打包为一个原子操作。中间状态不对外可见。

---

## 2. cart_add.lua —— 5 命令原子的加购

```lua
-- KEYS: items, checked, sort
-- ARGV: skuId, quantity, maxCartSize, maxItemQuantity, timestamp
-- 返回: >0=当前数量, -1=购物车已满

local exists = redis.call('HEXISTS', itemsKey, skuId)      -- ① 是否已存在

if exists == 0 then                                         -- ② 新商品才检查上限
    local currentSize = redis.call('HLEN', itemsKey)
    if currentSize >= maxCartSize then return -1 end
end

local newQuantity = redis.call('HINCRBY', itemsKey, skuId, quantity)  -- ③ 累加数量

if newQuantity > maxItemQuantity then                       -- ④ 单品截断
    redis.call('HSET', itemsKey, skuId, maxItemQuantity)
    newQuantity = maxItemQuantity
end

redis.call('SADD', checkedKey, skuId)                       -- ⑤ 默认选中

if exists == 0 then
    redis.call('ZADD', sortKey, 'NX', timestamp, skuId)     -- ⑥ 首次加购时间
end

return newQuantity
```

**五步逐一分析**：

| 步骤 | 命令 | 如果不原子会怎样 |
|:--:|------|------|
| ① | `HEXISTS` | 并发时另一个线程可能正在 HDEL（删除），当前线程拿到 stale 状态 |
| ② | `HLEN` + 判断 | 并发超上限：A 看到 49，B 也看到 49，都 HINCRBY → 51 个品种 |
| ③ | `HINCRBY` | 本身是原子的，但搭上前面的存在检查才有意义 |
| ④ | `HSET` 截断 | 如果不做截断，加购 500 个 → 库存管理的噩梦 |
| ⑤ | `SADD` | 默认选中——如果这步不在 Lua 中，用户加购后需要手动点选 |
| ⑥ | `ZADD NX` | NX=只添加新元素——已存在的 SKU 重复加购不改时间戳 |

**单品截断的策略选择**：Lua 脚本在超上限时执行 `HSET` 截断到 99 并返回截断后的值——对比 counter 的 DECR 归零保护（报错拒绝），cart 选择了静默策略。"用户想加 500 个？帮你限制到 99，你已经看到购物车里有 99 个了"。这种静默截断比报错更适合购物车场景——避免用户在移动端频繁看到错误提示而放弃加购。

---

## 3. cart_remove.lua —— 三结构原子删除

```lua
-- KEYS: items, checked, sort
-- ARGV: skuId
-- 返回: 1=成功, 0=不存在

local exists = redis.call('HEXISTS', itemsKey, skuId)
if exists == 0 then return 0 end

redis.call('HDEL', itemsKey, skuId)    -- Hash
redis.call('SREM', checkedKey, skuId)  -- Set
redis.call('ZREM', sortKey, skuId)     -- ZSet
return 1
```

**与 add 脚本的差异**：remove 没有业务判断（不需要检查上限、不需要截断），只有纯删除。添加了 `HEXISTS` 检查——如果商品不存在，返回 0 而不是执行空删除。这防止了无效的 Redis 命令消耗。

**如果不原子**：`HDEL` 执行后 → 线程切换 → `getCartList` 执行 `HGETALL`（看不到 skuId）+ `SISMEMBER`（Set 中仍可见 skuId）→ 幽灵商品。

---

## 4. cart_check_all.lua —— 全选原子的竞态防护

```lua
-- KEYS: items(Hash), checked(Set)
-- ARGV: "1"=全选, "0"=取消全选

if checked == '0' then
    redis.call('DEL', checkedKey)  -- 取消全选：直接 DEL（O(1)）
    return 0
else
    local allSkuIds = redis.call('HKEYS', itemsKey)  -- 获取所有 SKU
    redis.call('DEL', checkedKey)                     -- 清空旧 Set
    for i = 1, #allSkuIds do
        redis.call('SADD', checkedKey, allSkuIds[i])  -- 逐个添加
    end
    return #allSkuIds
end
```

**竞态场景（如果不原子）**：

```
线程A: checkAll(true)                         线程B: addToCart(skuId=999)
  ① HKEYS → [100, 101, 102]                     Lua: HINCRBY + SADD(checked, 999)
  ② DEL checked                                   → checked Set 现在含 {999}
  ③ SADD 100, 101, 102                           → checked Set 被 A 的 SADD 覆盖
  → 999 在 Hash 中但不在 checked Set 中！       → 丢失选中状态
```

Lua 原子保证：① HKEYS + ② DEL + ③ SADD×N 在同一个执行单元中——线程 B 的 addToCart 在 Redis 队列中等待脚本执行完毕后才开始。

**为什么用循环 SADD 而不是单次 SADD 多参数？**

Redis `SADD key member1 member2 ... memberN` 支持一次添加多个成员。Lua 脚本用 `for` 循环的原因是 Lua 的 table 到 Redis 多参数传递需要解析转换——对于购物车（上限 50），循环开销可以忽略。日志中已有 "selectedCount=20" 的统计确认正确性。

**取消全选的优化**：`checked == '0'` 时直接 `DEL checkedKey`——O(1) 操作。不需要先获取 HKEYS 再判断——Set 为空和直接删都是合法状态。

---

## 5. 三个脚本的注册与加载

```java
// RedisScriptConfig.java — 预加载为 Spring Bean
@Bean public DefaultRedisScript<Long> cartAddScript()     → lua/cart_add.lua
@Bean public DefaultRedisScript<Long> cartRemoveScript()  → lua/cart_remove.lua
@Bean public DefaultRedisScript<Long> cartCheckAllScript()→ lua/cart_check_all.lua
```

**预加载优势**：
- `ClassPathResource` 在应用启动时读取 `.lua` 文件内容到内存
- 首次执行时 Spring Data Redis 计算 SHA1 并注册到 Redis
- 后续调用走 `EVALSHA`（只发送 SHA1 哈希）而非 `EVAL`（发送完整脚本）
- Redis 重启后 SHA1 缓存丢失 → Spring Data Redis 自动回退到 `EVAL` 重建缓存

**返回类型统一为 `Long.class`**：三个脚本的返回值都是整数（数量、0/1、选中数）。Spring Data Redis 的 `DefaultRedisScript<Long>` 自动将 Redis 的整数返回转换为 Java Long。

---

## 6. 发散：与 counter 模块 Lua 对比

| 维度 | cart Lua | counter Lua |
|------|------|------|
| 脚本数量 | 3（add/remove/checkAll） | 3（DECREMENT + INCR_DEDUP + 归零保护） |
| Key 数量 | 2-3（多结构操作） | 1-2（单结构） |
| 复杂度 | add（5 命令 + 2 分支）、checkAll（循环 N 次 SADD） | INCR_DEDUP（去重 + INCR + 归零，3 分支） |
| Hash Tag | ✅ `{userId}` 三 Key 同 slot | ❌ 不需要 |
| 返回值的业务分支 | add: -1(满) / >0(数量)；remove: 0/1 | INCR_DEDUP: [1,count] / [0,0] / [-1,0] |
| 截断策略 | 静默截断到上限（99） | 归零保护报错 |

cart 的 Lua 比 counter 多一个维度——不仅要保证单 Key 操作的原子性，还要保证多 Key 之间的结构一致性（Hash/Set/ZSet 不能出现幽灵数据）。counter 只需要保证单 Key 的数值正确性。

**并发安全性对比**：

```
counter: 两个线程同时 INCR → Redis INCR 是原子操作 → 安全（不需要 Lua）
         两个线程同时 DECR → 需要 Lua（GET + 比较 + DECR 原子）

cart:    两个线程同时 addToCart → Lua（5 命令原子，防超上限）
         两个线程同时 checkAll → Lua（防丢失选中状态）
         修改数量 → HSET 单命令原子 → 不需要 Lua
         勾选 → SADD/SREM 幂等 → 不需要 Lua
```

---

## 关联文档

- `01-cart-module.md` — §4 Lua 脚本原子性
- `02-cart-test.md` — §1.1/§1.5/§1.7
- `03-redis-structure.md` — 为什么是三结构（Lua 脚本的存在前提）
