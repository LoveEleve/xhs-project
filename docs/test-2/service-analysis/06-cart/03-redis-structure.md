# Redis 三结构协同设计

> 源码：`CartService.java` KEYS + `cart_add.lua` + `cart_remove.lua` + `cart_checkall.lua`
> 验证：`02-cart-test.md` §1.1

---

## 1. 为什么是三个结构？

购物车数据可以用一个大 JSON 存在一个 Redis Key 里：

```
myxhs:cart:10001 → {"2081544572120371202":{"qty":3,"checked":true,"time":1234}, ...}
```

但实际上用了三个独立的 Redis 数据类型：

```
myxhs:cart:{10001}:items   — Hash   field=skuId, value=quantity
myxhs:cart:{10001}:checked — Set    member=skuId
myxhs:cart:{10001}:sort    — ZSet   member=skuId, score=timestamp
```

**选择三结构而不是单 JSON 的理由**：

| 操作 | 单 JSON（GET→修改→SET） | 三结构 |
|------|:---:|:---:|
| 加购 +1 | GET→parse→qty++→SET（2 往返，非原子） | HINCRBY（1 往返，原子） |
| 修改数量 | GET→parse→SET（2 往返） | HSET（1 往返，原子） |
| 获取数量 | GET→parse→get skuId.qty（1 往返+解析） | HGET（1 往返） |
| 勾选 | GET→parse→checked=true→SET（2 往返） | SADD（1 往返，原子） |
| 全选 | GET→parse→遍历改→SET（复杂） | Lua: HKEYS→DEL→SADD |
| 删除 | GET→parse→delete skuId→SET（2 往返） | Lua: HDEL+SREM+ZREM |
| 按时间排序 | GET→parse→sort by time（应用层排序） | ZREVRANGE（Redis 层排序） |
| 判断是否选中 | GET→parse→check（1 往返+解析） | SISMEMBER（1 往返） |

三结构把"修改单个字段"变成了独立的原子操作——不需要读-改-写的循环。

---

## 2. 每个结构的职责

### 2.1 Hash(items)：商品 + 数量

```
HGETALL myxhs:cart:{10001}:items
→ {"2081544572120371202":"3", "2081544572120371205":"1"}
```

**为什么用 Hash？**
- `HSET`：设置数量，O(1)
- `HINCRBY`：增减数量（加减购），O(1)，原子，不需要先 GET 再 SET
- `HDEL`：删除商品，O(1)
- `HGETALL`：获取全部，O(n)（购物车最多 50 种，极快）
- `HLEN`：获取品种数（角标），O(1)

### 2.2 Set(checked)：选中状态

```
SMEMBERS myxhs:cart:{10001}:checked
→ {"2081544572120371202", "2081544572120371205"}
```

**为什么用 Set？**
- `SADD`/`SREM`：勾选/取消勾选，O(1)，幂等
- `SISMEMBER`：判断单个商品是否选中，O(1)
- `SCARD`：获取选中数量，O(1)
- 天然去重——同一个 skuId 不会出现两次

勾选状态不放在 Hash 中（不用 `HSET items skuId "3|checked"`）因为：
- 修改勾选时不想碰数量字段——两个独立维度的操作不应该耦合
- `HGETALL` 获取所有商品时，不需要解析勾选状态——通过 `SISMEMBER` 单独判断

### 2.3 ZSet(sort)：加购时间排序

```
ZREVRANGE myxhs:cart:{10001}:sort 0 -1 WITHSCORES
→ [("2081544572120371205", 1785138000), ("2081544572120371202", 1785137933)]
```

**为什么用 ZSet？**
- `ZADD key NX score member`：记录首次加购时间（NX=不存在时才写入，已存在不更新）
- `ZREVRANGE 0 -1`：按时间倒序排列（最近加购最前面），O(log n + m)
- `ZREM`：删除商品时同步删除排序记录（在 Lua 原子脚本中）

**为什么不用 Redis List（LPUSH 到列表头）？**
- List 无法去重——同一 skuId 多次加购会产生重复项
- List 按插入顺序，不是时间戳——无法表达"用最新时间覆盖"的语义

---

## 3. Lua 原子脚本：三结构需要同步更新

三个结构是独立的——删除一个商品需要同时删三个 Key。分三次命令执行有中间状态：

```
HDEL items skuId        ← 商品从 Hash 中删除
SREM checked skuId      ← 之前可能崩溃，Set 中残留
ZREM sort skuId         ← 之前可能崩溃，ZSet 中残留

→ 问题：如果一个线程删完 HDEL 后被切换，另一个线程执行 getCartList：
  HGETALL → 看不到该商品
  SISMEMBER → 但 checked Set 中还存在该 skuId！
  → 幽灵商品：Hash 中不存在，但显示为"已选中"
```

**Lua 脚本解决方案**（`cart_remove.lua`）：

```lua
local skuId = ARGV[1]
redis.call('HDEL', KEYS[1], skuId)   -- items Hash
redis.call('SREM', KEYS[2], skuId)   -- checked Set
redis.call('ZREM', KEYS[3], skuId)   -- sort ZSet
return 1
```

三操作在 Redis 单线程中原子执行——中间状态对其他线程不可见。

**三个 Lua 脚本覆盖的场景**：

| 脚本 | 操作 | 如果非原子会怎样 |
|------|------|------|
| `cart_add.lua` | HEXISTS+HLEN+HINCRBY+截断+SADD+ZADD | 并发超上限 50 |
| `cart_remove.lua` | HDEL+SREM+ZREM | 幽灵商品（Hash 已删 Set 还有） |
| `cart_checkall.lua` | HKEYS+DEL+SADD | 并发 addToCart 时新商品丢失选中 |

---

## 4. Hash Tag 与 Redis Cluster

```
myxhs:cart:{10001}:items
myxhs:cart:{10001}:checked
myxhs:cart:{10001}:sort
```

`{10001}` 是大括号 hash tag——Redis Cluster 只对 `{}` 内的内容计算 slot 哈希。三个 Key 因为 `{10001}` 相同，必定落在同一个 Redis 节点上。

**没有 hash tag 会怎样？**

```
myxhs:cart:10001:items   → slot 1234（节点 A）
myxhs:cart:10001:checked → slot 5678（节点 B）
myxhs:cart:10001:sort    → slot 9012（节点 C）

执行 cart_remove.lua（3 个 Key）→ CROSSSLOT error！
```

Lua 脚本要求所有操作的 Key 在同一个 Redis 节点上。没有 hash tag，三个 Key 被随机分配到三个节点，Lua 脚本无法执行。

**与 counter 模块的对比**：

| 模块 | Redis Key | 是否需要 Hash Tag | 原因 |
|------|------|:---:|------|
| counter | `myxhs:counter:{targetType}:{targetId}:{countType}` | ❌ | Lua 只操作单个 Key |
| cart | `myxhs:cart:{userId}:items/checked/sort` | ✅ | Lua 操作三个 Key |
| analytics | `myxhs:social:follow:{userId}` | ✅ | `follow_self.lua` 操作双 ZSet |

**前瞻设计**：当前部署使用 Redis Sentinel（单节点），hash tag 没有实际作用——三个 Key 肯定在同一节点。但加 hash tag 是为 Redis Cluster 迁移做准备——切换到 Cluster 后不需要改 Key 格式。

---

## 5. 发散：购物车与电商秒杀的冲突

电商秒杀场景下，购物车的"数量上限 99"和"品种上限 50"会影响秒杀体验：

| 场景 | 当前行为 | 问题 |
|------|------|------|
| 秒杀时加到 99 个 | Lua 截断到 99（静默） | 用户看到购物车有 99 个，实际秒杀限量可能 1 个 |
| 购物车已有 50 种 | 返回 -1 拒绝 | 无法抢新的秒杀商品 |

**改进方向**：秒杀商品不应该走购物车流程——从商详页直接"立即购买"跳订单页。当前 cart 模块不处理秒杀场景，这是 order 模块的职责。

---

## 关联文档

- `01-cart-module.md` — §3 Redis 三结构
- `02-cart-test.md` — §1.1 三结构验证
- `04-lua-scripts.md` — Lua 脚本详解（三个 .lua 文件）
