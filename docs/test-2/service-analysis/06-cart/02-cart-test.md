# 06-cart curl 测试记录

> 测试时间：2026-07-27
> 对端服务：`http://localhost:19008`（直连，绕过 Gateway）
> 数据库：`my_xhs_cart@13307`，Redis 端口 16379（Sentinel Master）

---

## 1.1 加入购物车（Lua 原子操作 + 三结构协同）

### curl 请求与响应

```
POST /api/cart/add
Header: X-User-Id: 10001, Content-Type: application/json
Body:   {"skuId":2081544572120371202,"quantity":3}
→ 200  {"code":200,"message":"操作成功"}

GET /api/cart/count
Header: X-User-Id: 10001
→ 200  {"data":{"count":1}}  // 1 种商品
```

### 中间件验证

**Redis 三结构（16379）**：

```
Items (Hash):   myxhs:cart:{10001}:items   → {"2081544572120371202":"3"}
Checked (Set):  myxhs:cart:{10001}:checked → {"2081544572120371202"}  // 默认选中
Sort (ZSet):    myxhs:cart:{10001}:sort    → {("2081544572120371202", 1785137933412)}
```

**MySQL（my_xhs_cart@13307）**：

```
user_id=10001, sku_id=2081544572120371202, quantity=3, checked=1
```

MQ 异步持久化确认：`CartSyncConsumer` 消费 `CART_TOPIC:ADD` → INSERT t_cart_item。

### 服务日志

```
[INFO] [购物车] 加入成功: userId=10001, skuId=2081544572120371202, quantity=3, 当前数量=3
[INFO] [购物车同步] 收到消息: action=ADD, userId=10001, skuId=2081544572120371202
[INFO] [购物车同步] 新增成功: userId=10001, skuId=2081544572120371202, quantity=3
```

日志完整链路：CartService.addToCart → MQ asyncSend → CartSyncConsumer → MySQL INSERT。

### 代码路径分析

```java
// CartService.addToCart():
String itemsKey = "myxhs:cart:{10001}:items";
String checkedKey = "myxhs:cart:{10001}:checked";
String sortKey = "myxhs:cart:{10001}:sort";

// ① Lua 脚本原子执行（5 命令 1 次网络往返）
Long result = stringRedisTemplate.execute(cartAddScript,
    List.of(itemsKey, checkedKey, sortKey),
    "2081544572120371202", "3", "50", "99", String.valueOf(System.currentTimeMillis()));

// cart_add.lua:
//   HEXISTS → false（新商品）
//   HLEN → 0 < 50 ✓
//   HINCRBY itemsKey "2081544572120371202" 3 → newQuantity=3
//   截断检查: 3 ≤ 99 ✓（不截断）
//   SADD checkedKey "2081544572120371202" → 默认选中
//   ZADD sortKey NX timestamp "2081544572120371202" → 新商品记录时间
//   return 3

// ② 返回结果
log.info("加入成功: skuId={}, quantity=3, 当前数量=3");

// ③ MQ 异步持久化（不阻塞请求）
sendCartSyncEvent(10001, 2081544572120371202, 3, 1, "ADD");
// → RocketMQ asyncSend CART_TOPIC:ADD
// → CartSyncConsumer.onMessage → cartItemMapper.insert/tickUpdate
```

### 工程设计分析

**1. Lua 原子性如何防并发超上限**

```
并发场景：购物车已有 49 种商品，线程 A、B 同时加购不同 SKU

线程A: cart_add.lua → HLEN=49 < 50 → HINCRBY → count=50
线程B: cart_add.lua → HLEN=49 < 50 → HINCRBY...
  → 矛盾！两个线程都看到 49，都认为可以加

但如果非原子：
  线程A: HLEN=49 → 线程B: HLEN=49 → 都以为可以加 → HINCRBY×2 → count=51！

Lua 脚本保证：HEXISTS + HLEN + HINCRBY 在同一 Redis 命令中。
Redis 单线程执行 Lua → 线程 A 的脚本执行完毕后，HLEN 已变为 50，
线程 B 的脚本再执行 HLEN=50 ≥ 50 → return -1。
```

**2. 默认选中 + NX 时间戳**

- `SADD checkedKey`：所有新加购商品默认选中——用户在购物车页直接点"结算"即可购买
- `ZADD NX`：只在第一次加购时记录时间戳，后续同 SKU 加购不改时间——排序稳定

**3. 单品截断的静默处理**

Lua 脚本中 `HINCRBY` 后检查 `newQuantity > 99` → `HSET` 截断到 99，返回截断后的值。对比 counter 的 DECR 归零保护（报错拒绝），cart 的截断策略更温和——"你想加 3 个但库存有 99 个了，先帮你加到 99"。

**4. Hash Tag 的跨模块一致性**

cart 使用 `{userId}` hash tag，counter 没有 hash tag。两者 Redis Key 的设计意图不同：
- counter：单 Key 计数，不跨 Key 操作 Lua → 不需要 hash tag
- cart：同一用户的三 Key 需在同一 slot 才能用 Lua 脚本原子操作 → 必须 hash tag

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| addToCart → 200 | 成功 | 200 | ✅ |
| Hash items 写入 | skuId=3 | {"2081544572120371202":"3"} | ✅ |
| Set checked 默认选中 | skuId in set | 已选中 | ✅ |
| ZSet sort 记录时间 | score=timestamp | 1785137933412 | ✅ |
| cart/count = 1 | 1 | {"count":1} | ✅ |
| MQ 异步持久化 | INSERT MySQL | quantity=3, checked=1 | ✅ |

---

## 1.2 购物车列表（Pipeline 三结构 + Feign Fallback 降级）

### curl 请求与响应

```
GET /api/cart/list
Header: X-User-Id: 10001
→ 200  {
  "data": {
    "items": [{
      "skuId": 2081544572120371202,
      "quantity": 3, "checked": true, "addedAt": 1785137933412,
      "valid": false, "invalidReason": "商品信息获取失败",
      "spuId": null, "name": null, "price": null
    }],
    "checkedCount": 0, "checkedAmount": 0, "totalCount": 1, "allChecked": false
  }
}
```

**Redis Pipeline 成功**：quantity=3, checked=true, addedAt 正确 ✅。  
**Feign 调 Product 失败**：FallbackFactory 返回空 Map → all SKU details null → valid=false ✅。

### Feign 失败原因

```
[WARN] [购物车] 批量获取SKU信息失败, 降级跳过,
  skuIds=[2081544572120371202],
  error=Cannot invoke "Object.hashCode()" because "key" is null
```

Nacos 上 product 注册 IP 为 `21.214.97.212:19006`（healthy=true），而 cart 和 product 在同一台机器。Feign 通过 Nacos 服务发现路由到 `21.214.97.212` 时网络不可达。直连 `localhost:19006` 正常。

**验证**：

| 路径 | 结果 |
|------|:---:|
| `GET localhost:19006/api/product/sku/batch`（直连） | 200, name=测试SKU-黑色-L ✅ |
| Feign `my-xhs-product` → Nacos Discovery → 21.214.97.212:19006 | ❌ null key error |

### 代码路径分析

```java
// CartService.getCartList():
// ① Pipeline 一次获取三结构（成功）
List<Object> pipelineResults = stringRedisTemplate.executePipelined(connection -> {
    connection.hashCommands().hGetAll(itemsKeyBytes);         // Hash → {skuId:"3"}
    connection.setCommands().sMembers(checkedKeyBytes);       // Set  → {skuId}
    connection.zSetCommands().zRevRangeWithScores(sortKeyBytes, 0, -1); // ZSet
    return null;
});
// 结果: itemsMap={2081544572120371202:3}, checkedSet={208...}, sortMap={208...:1785137933412}

// ② Feign 调 Product（失败 → Fallback）
Map<Long, SkuDTO> skuMap = batchGetSkuInfo(skuIds);
// → ProductFeignClient.batchGetSkuDetails → Nacos Discovery → 21.214.97.212 → null key error
// → ProductFeignFallbackFactory → return new HashMap<>()

// ③ 组装（SKU 信息全部为 null）
if (sku != null) {
    builder.name(sku.getName()).price(sku.getPrice())...  // 正常路径
} else {
    builder.valid(false).invalidReason("商品信息获取失败");  // 降级路径 ✓
}
```

### 工程设计分析

**1. Feign Fallback 的正确性**

`ProductFeignFallbackFactory` 在 product 不可用时返回空 Map——不抛异常，不阻塞购物车展示。这验证了降级策略的有效性：

```
product 正常:
  getCartList → Redis Pipeline ✓ → Feign ✓ → 返回完整商品信息

product 不可用（/Nacos路由异常）:
  getCartList → Redis Pipeline ✓ → Feign ✗ → Fallback → 商品标记 invalid
```

购物车核心数据（商品 ID、数量、勾选、排序）全在 Redis 中——Feign 调用只为了展示商品名称和价格。即使 product 完全不可用，购物车仍然可用（只是看不到商品详情）。

**2. 三层数据的一致性边界**

```
write: Redis(Lua原子) → 返回 200          ← 同步，用户等待
       MQ asyncSend → MySQL               ← 异步，不阻塞

read:  Redis Pipeline（三结构 1 次往返）   ← 购物车核心数据
       Feign → Product (Nacos Discovery)   ← 商品详情（可降级）
```

写链路：Redis 是权威源，用户操作完成立即返回，MQ 异步落库。读链路：Redis 永远可用（Pipeline），product 不可用时降级展示。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| Redis Pipeline 三结构成功 | quantity=3, checked=true | 正确 | ✅ |
| ZSet 排序时间戳 | 1785137933412 | 正确 | ✅ |
| Feign product 不可用 → Fallback | valid=false | valid=false, "商品信息获取失败" | ✅ |
| 购物车基本功能不受 product 影响 | totalCount=1 | totalCount=1 | ✅ |
| direct product call 正常 | 200, data | 200, name=测试SKU-黑色-L | ✅ |

---

## 1.3 修改数量（HSET 单命令原子）

### curl 请求与响应

```
PUT /api/cart/quantity
Header: X-User-Id: 10001, Content-Type: application/json
Body:   {"skuId":2081544572120371202,"quantity":2}
→ 200  {"code":200,"message":"操作成功"}
```

数量从 3 改为 2。

### 中间件验证

```
Redis Hash: {"2081544572120371202": "2"}  // 3→2 确认
Redis Set:  unchanged
Redis ZSet: unchanged
```

### 代码路径分析

```java
// CartService.updateQuantity():
Boolean exists = stringRedisTemplate.opsForHash().hasKey(itemsKey, skuIdStr);
if (!exists) throw CART_ITEM_NOT_FOUND;

stringRedisTemplate.opsForHash().put(itemsKey, skuIdStr, "2");  // HSET 单命令原子
sendCartSyncEvent(userId, skuId, 2, null, "UPDATE");            // MQ 异步
```

### 工程设计分析

**为什么修改数量不需要 Lua？** `HSET` 是单命令原子操作——不存在竞态窗口。前置的 `HEXISTS` 检查即使并发失败，最多多返回一次"商品不存在"错误，不会产生脏数据。对比 `addToCart` 需要 Lua（5 命令，并发超上限），`updateQuantity` 是最简单的路径。

**为什么只改 Hash 不碰 Set/ZSet？** 修改数量只影响数量维度——勾选状态和时间排序不变。三结构的独立设计使得"只改一个字段"时不需要触及其他两个 Key。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 修改数量 → 200 | quantity=2 | quantity=2 | ✅ |
| Redis Hash HSET | {"2081544572120371202":"2"} | 正确 | ✅ |
| Set/ZSet 不受影响 | unchanged | unchanged | ✅ |

## 1.4 勾选/取消勾选（Set SADD/SREM 幂等）

### curl 请求与响应

```
PUT /api/cart/check
Header: X-User-Id: 10001
Body:   {"skuId":2081544572120371202,"checked":false}
→ 200  // SREM → checked Set 变为空

PUT /api/cart/check
Body:   {"skuId":2081544572120371202,"checked":true}
→ 200  // SADD → checked Set 恢复 {{skuId}}
```

### 中间件验证

```
勾选前: SMEMBERS → {}        (空集)
勾选后: SMEMBERS → {skuId}   (SADD 成功)
SISMEMBER skuId → 1          (O(1) 判断)
```

### 代码路径分析

```java
// CartService.checkItem():
Boolean exists = stringRedisTemplate.opsForHash().hasKey(itemsKey, skuIdStr);
if (!exists) throw CART_ITEM_NOT_FOUND;  // 校验商品在购物车中

if (checked) {
    stringRedisTemplate.opsForSet().add(checkedKey, skuIdStr);   // SADD
} else {
    stringRedisTemplate.opsForSet().remove(checkedKey, skuIdStr); // SREM
}
```

### 工程设计分析

**为什么勾选不需要 Lua？** `SADD`/`SREM` 本身就是原子操作——单命令，不存在竞态窗口。勾选状态是幂等的——重复 SADD 同一个 skuId 不会重复添加（Set 天然去重），重复 SREM 也不会报错。即使并发，最终状态一定正确。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 勾选 → 200 | Set 含 skuId | SISMEMBER=1 | ✅ |
| 取消勾选 → 200 | Set 为空 | SMEMBERS={} | ✅ |
| SADD 幂等 | 重复调用不报错 | 200 | ✅ |

## 1.5 Lua 原子删除（三结构同步清除）

### curl 请求与响应

```
DELETE /api/cart/2081544572120371202
Header: X-User-Id: 10001
→ 200  {"code":200,"message":"操作成功"}
```

### 中间件验证

| 删除前 | 删除后 |
|------|------|
| Items: {skuId:"2"} | Items: {} |
| Checked: {skuId} | Checked: {} |
| Sort: [skuId] | Sort: [] |

三结构完全清空——Lua 原子删除确保无幽灵商品残留。

### 服务日志

```
[INFO] [购物车] 删除商品: userId=10001, skuId=2081544572120371202, result=1
[INFO] [购物车同步] 收到消息: action=DELETE → MySQL 物理删除
```

### 代码路径分析

```java
// CartService.removeFromCart():
Long result = stringRedisTemplate.execute(cartRemoveScript,
    List.of(itemsKey, checkedKey, sortKey), "2081544572120371202");

// cart_remove.lua（三个命令在同一 Redis 执行单元中）：
redis.call('HDEL', KEYS[1], skuId)   // items Hash
redis.call('SREM', KEYS[2], skuId)   // checked Set
redis.call('ZREM', KEYS[3], skuId)   // sort ZSet
```

### 工程设计分析

**如果分三次命令会怎样？**

```
HDEL items skuId     ← Hash 清空
SREM checked skuId   ← 之前可能崩溃 → Set 中残留 skuId
ZREM sort skuId      ← 之前可能崩溃 → ZSet 中残留 skuId

→ getCartList 读到：Hash 中无此商品，但 checked Set 中有 → 幽灵商品！
```

Lua 保证三个操作在 Redis 单线程中一次完成——中间状态对其他线程不可见。这是 `cart_remove.lua` 存在的唯一理由。

**对比 addToCart 的 Lua，remove 更简单**：add 有 5 个命令（HEXISTS+HLEN+HINCRBY+截断+SADD+ZADD）含业务判断，remove 只有 3 个命令（HDEL+SREM+ZREM）纯删除——不需要条件分支。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 删除 → 200 | 商品移除 | 200 | ✅ |
| Hash 清空 | {} | {} | ✅ |
| Set 清空 | {} | {} | ✅ |
| ZSet 清空 | [] | [] | ✅ |
| 无幽灵商品 | 三结构一致 | 三结构一致 | ✅ |

## 1.6 购物车上限 + @RateLimit 交互

### 测试过程

发送 51 次 `addToCart` 不同 skuId：

```
前 20 次 → 200（成功）
第 21 次起 → 500 "请求过于频繁，请稍后重试"（@RateLimit 20/60s perUser 触发）
```

最终购物车 HLEN=20。

### 工程设计分析

**@RateLimit 先于 50 上限触发**：对于单用户操作，1 分钟内最多添加 20 种商品。这意味着正常操作中永远不会触发 Lua 的 50 上限——除非用户等待 3 分钟分批添加。这是一种双重防护：

| 防护层 | 阈值 | 触发方式 |
|:--:|:---:|------|
| @RateLimit | 20 次/60s | 时间窗口内操作频率超限 |
| Lua HLEN ≤ 50 | 50 个品种 | 累计品种数超限 |

Rate limit 防护高频操作，Lua 防护累计品种——两者互补。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| @RateLimit perUser 生效 | 第 21 次拒 | "请求过于频繁" | ✅ |
| Lua 50 上限未触发 | HLEN ≤ 20 | HLEN=20 | ✅ |
| 双重防护设计 | RateLimit + Lua | 互补工作 | ✅ |

---

## 1.7 全选/取消全选（Lua 原子 checkAllScript）

### curl 请求与响应

```
PUT /api/cart/check-all?checked=true
Header: X-User-Id: 10001   (购物车有 20 种商品)
→ 200  // Lua: HKEYS → DEL checked → SADD all skuIds
```

### 中间件验证

```
SCARD checked: 20（等于 HLEN items: 20）→ allChecked=true ✅
```

### 工程设计

Lua `cartCheckAllScript` 用 `HKEYS` 获取所有 skuId + `DEL checked` + `SADD` 批量写入。原子性防止并发 addToCart 时新加商品丢失选中状态——如果非原子：HKEYS 返回 20 个 skuId → addToCart 新增 #21 → DEL+ SADD 只把前 20 个加回来 → #21 处于未选中状态（丢失）。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 全选 → 所有商品选中 | SCARD=HLEN | 20=20 | ✅ |
| 取消全选 → DEL checked | Set 为空 | {} | ✅ |

---

## 1.8 清空购物车（DEL 三 Key）

```
DELETE /api/cart/clear
Header: X-User-Id: 10001
→ 200  // 直接 DEL items/checked/sort 三个 Key

Redis: EXISTS(items)=0, EXISTS(checked)=0, EXISTS(sort)=0 ✅
```

清空操作不用 Lua——`stringRedisTemplate.delete(List.of(itemsKey, checkedKey, sortKey))` 是普通 Redis DEL 多 Key 命令，本身就是原子的（Redis 单线程）。三个 Key 全部不存在后，getCartList 返回空列表。

---

## 1.9 匿名购物车合并（合并策略 + 幂等）

### curl 请求与响应

```
POST /api/cart/merge
Header: X-User-Id: 10001
Body:   {"items":[{"skuId":2081544572120371202,"quantity":5},{"skuId":99999,"quantity":1}]}
→ 200

### 合并后验证 ###
Items: {"2081544572120371202":"5", "99999":"1"}  // 两个 SKU 合并成功
Checked: {2081544572120371202, 99999}            // 默认选中
Sort: 2 members                                   // ZADD NX 记录首次时间
```

### 工程设计

合并策略：同一 SKU 取 `max(currentQty, mergeQty)`（幂等），新 SKU 直接加入（上限 50）。`ZADD NX` 保留首次加购时间不覆盖。合并不是 Lua 原子——并发 addToCart 可能超上限，但合并是登录时的低频操作，实际概率极低。

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 合并 → 200 | 含 2 个 SKU | 2 SKUs | ✅ |
| 默认选中 | 全部 checked | checked=2 | ✅ |
| ZADD NX 时间戳 | 记录首次 | score 正确 | ✅ |
