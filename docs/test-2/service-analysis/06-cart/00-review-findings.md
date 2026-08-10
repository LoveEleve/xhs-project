# 06-cart 深度审查记录

> **审查日期**: 2026-08-05  
> **审查范围**: 34 源码/配置/Lua/测试文件全量  
> **发现**: 1🔴严重 + 9🔴高 + 5🟡中 + 11🟡低 = 26 项

---

## 🔴 严重 (1)

### C-01: 对账只遍历 MySQL 用户，Redis-only 购物车永不修复

**证据**: `CartReconcileJob.java:69` 使用 `cartItemMapper.selectDistinctUserIds()`（`CartItemMapper.java:20`，`SELECT DISTINCT user_id FROM t_cart_item`）枚举用户。

**影响**: 若某用户在 MySQL 中一条 `t_cart_item` 记录都没有（首次加购 MQ 全丢失），该用户不在对账遍历列表 → 场景 "Redis 有 + MySQL 无 → INSERT" 对其永远不生效 → **MQ 全丢 = 备份永久丢失**。

**修复方案**: 
- 方案 A（主路径）：增加单用户对账端点 `POST /api/cart/internal/reconcile?userId=xxx`。`CartReconcileJob` 已有 `private int reconcileUser(Long userId)` 方法（行 95），改为 `public` 暴露即可。Controller 加 `X-Admin-Call` 校验。
- 方案 B（补充）：对账 Job 中增加 `SCAN 0 MATCH myxhs:cart:{*}:items COUNT 100` 枚举 Redis 侧 userId，与 MySQL `selectDistinctUserIds()` 取并集
- 注意事项：SCAN 需要处理 hash tag `{userId}` 模式，且对线上大 key 空间性能需评估

---

## 🔴 高 (9)

### C-02: updateQuantity check-then-act，并发删除后商品"复活"

**证据**: `CartService.java:154-160` — 先 `hasKey` 再 `HSET`。并发 `removeFromCart`（Lua HDEL+SREM+ZREM）在两步之间执行 → `HSET` 重新创建 Hash field → 已删除商品复活（checked/sort 两结构已被删，数据不一致）。注释（行 142-147）声称"即使并发也不会产生脏数据"——**与行为矛盾**。

**修复方案**: 改用 Lua 原子脚本：
```lua
-- cart_update_quantity.lua
-- KEYS[1] = cart:items:{userId}
-- ARGV[1] = skuId, ARGV[2] = newQuantity
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return 0 end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
return 1
```
若返回 0 → 抛 CART_ITEM_NOT_FOUND（商品已被并发删除）。该 Lua 替代现有的 `hasKey + HSET` 两步操作。

### C-03: merge 非原子，并发突破 50 品种上限

**证据**: `CartService.java:437-468` — `HLEN` → 循环 `HEXISTS/HGET/HSET/SADD/ZADD` 多次网络往返，不在同一 Lua/事务内。

**影响**: 两个并发 merge（或 merge + 并发 add）同时读到 size=45，各自加 6 个 → 实际 57 个品种，突破 MAX_CART_SIZE=50。对比 `cart_add.lua` 的原子上限检查——merge 是唯一绕过上限的路径。

**修复方案**: merge 有两类操作，需区别对待：
- **已有商品（branch 1）**：`max(currentQty, incomingQty)` 是收敛操作，多并发最终一致，保持现有逻辑
- **新商品（branch 2）**：改用 Lua 原子加入——复用 `cart_add.lua` 的 `HINCRBY + 上限检查 + SADD + ZADD` 模式（或改为逐项调用 `addToCart`，但需注意 `addToCart` 是 HINCRBY 增量语义而非 HSET 绝对值——merge 场景下匿名购物车可能已有多次加购的累积数量，应直接用绝对值 SET）

**更正后方案**：写一个新 Lua（`cart_merge_add.lua`），原子化执行 `HLEN 上限检查 + HSET + SADD + ZADD NX`。与 `cart_add.lua` 的区别：使用 HSET 绝对值（非 HINCRBY 增量），因为合并的 quantity 是匿名购物车的累积值。

### C-04: CLEAR 事件与加购竞争，误删新购物车 MySQL 行

**证据**: `CartService.java:493-505` — 先 DEL Redis 3 个 key → 异步发 CLEAR 事件。若清空后立刻加购 → ADD 事件先到消费者、CLEAR 后到 → `CartSyncConsumer.clearCartItems`（`CartSyncConsumer.java:130-136`）按 `user_id` 全删 MySQL 行 → 新加购行的 MySQL 备份丢失。

**修复方案**: `CartSyncEvent` 继承 `AbstractDomainEvent`，已有 `getTimestamp()` 字段。Consumer 侧：
```java
// clearCartItems — 改为仅删除 clear 操作之前的行
private void clearCartItems(CartSyncEvent event) {
    int deleted = cartItemMapper.delete(
        new LambdaQueryWrapper<CartItem>()
            .eq(CartItem::getUserId, event.getUserId())
            .lt(CartItem::getCreatedAt, event.getTimestamp())  // 仅删旧行
    );
}
```
新加购行的 `createdAt` 晚于 CLEAR 的 `timestamp` → 不被删除。

### C-05: MQ 事件无版本/序号，乱序消费致 MySQL 过期

**证据**: ADD/UPDATE 事件携带绝对数量，`asyncSend` 无消息 key/有序队列。

**影响**: 加购(qty=1) 后删除 → DELETE 先到、ADD 后到 → MySQL 残留幽灵行。加购(→2) 再加购(→3) → 3 先到 2 后到 → MySQL 停在 2。事件有 `timestamp` 字段（`AbstractDomainEvent`）但消费者未使用。

**修复方案**: 消费者 `upsertCartItem` 增加时间戳判断（`CartItem` 已有 `updatedAt` 字段）：
```java
// upsertCartItem — 插入/更新前检查是否已有更新的数据
CartItem existing = cartItemMapper.selectOne(
    new LambdaQueryWrapper<CartItem>()
        .eq(CartItem::getUserId, event.getUserId())
        .eq(CartItem::getSkuId, event.getSkuId()));
LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimestamp(), ZoneId.systemDefault());
if (existing != null && existing.getUpdatedAt() != null
    && !existing.getUpdatedAt().isBefore(eventTime)) {
    return;  // DB 数据已等于或新于事件时间，跳过此旧事件
}
// 正常 INSERT → DuplicateKeyException → UPDATE
```
同时，DELETE 事件也做时序保护：
```java
// handleDelete — 仅当行未被更晚的事件覆盖时才删除
CartItem existing = cartItemMapper.selectOne(...);
if (existing == null) return;  // 已不存在，跳过
if (existing.getUpdatedAt() != null 
    && existing.getUpdatedAt().isAfter(eventTime)) {
    return;  // 行已被更晚的 ADD 事件重建，跳过此旧 DELETE
}
cartItemMapper.delete(...);
```

**已知局限**：ADD(旧)→DELETE(新) → MQ 逆序到达（DELETE 先、ADD 后）时，ADD 会重新创建已删除的行（ghost row）。此窗口内的不一致由购物车对账修复（C-01），对账任务比较 Redis 权威状态后 DELETE 该 ghost row。

### C-06: 硬编码 ADMIN_TOKEN + reconcile 无节流 + 同步长任务阻塞

**证据**: `CartController.java:133` `ADMIN_TOKEN = "myxhs-admin-2026"` 硬编码字符串 equals，无常量时间比较。`/internal/reconcile` 无 `@RateLimit`，同步调用 `cartReconcileJob.reconcile()` 可能运行数分钟阻塞 Tomcat 线程。

**修复方案**: 
- 加 `@RateLimit(windowSeconds=60, maxRequests=2)` 
- Token 迁入 Nacos 配置
- 改为 `CompletableFuture.runAsync()` 异步执行

### C-07: Gateway X-User-Id 追加非覆盖

**证据**: `GatewayAuthFilter.java:127-130` 使用 `mutate().header(USER_ID_HEADER, userId)` — WebFlux 的 `header()` 是**追加**。客户端自带 `X-User-Id` 时，gateway 注入的真实身份排在第二位 → 下游 `request.getHeader()` 取第一个值 → 永远不用真实身份。

**修复方案**: 改为覆盖模式：
```java
// GatewayAuthFilter.java:128 — 替换 .header() 为全量设置
.request(r -> r.headers(h -> {
    h.set(USER_ID_HEADER, userId);
    h.set(TRACE_ID_HEADER, traceId);
    // 同时剥离客户端传入的 X-User-Id，防止伪造
    h.remove("X-User-Id-Original"); // （如有客户端用别名绕过）
}))
```
**注意**: GatewayAuthFilter 的 header 注入影响所有下游服务，修改后需全量回归测试。

### C-08: 内部令牌硬编码共享 + cart 读接口缺校验

**证据**: `InternalCallFeignConfig.java:16` 令牌写死源码。cart 的 `/list`、`/count`、`/merge` 端点对 X-User-Id 完全信任——无任何内部调用校验。

**修复方案**: 
- 令牌迁入 Nacos 配置
- cart 读接口加 `X-Internal-Call` 校验（与 product 的 `batchGetSkuDetails` 一致）

### C-09: cart 库存展示与真实库存解耦

**证据**: cart 无 inventory Feign 客户端，库存来自 product 的 `SkuDTO.stock`（product 缓存/MySQL 值，可能与 inventory 实时值不一致）。

**修复方案**: 
- **不改变 cart 展示行为**——保持从 product 读取 stock 并标记 `stock<=0` 为失效。生产环境中 product 缓存 30min 刷新一次，库存延迟可接受（极端案例由下单时的 inventory 实时校验兜底）
- cart 侧加注释说明库存来自 product 缓存，非实时值
- 真正的库存判断在 order 创建时由 inventory 服务实时校验（下单时才做，不是购物车展示时做）

---

## 🟡 中 (5)

### C-10: product 批量过滤下架 SKU，cart "商品已下架" 分支成死代码

**证据**: `SkuService.java:98-100` 的 `batchGetSkuDetails` 带 `.eq(Sku::getStatus, ON_SHELF)` → 下架 SKU 直接过滤，不返回。`CartService.java:369-370` 的 `sku.getStatus() != 1 → "商品已下架"` 分支永远触发不了。

**修复方案**: 删除 cart 侧的死分支，或改为 product 返回包含 status 的全量 SKU（cart 侧自行过滤）。

### C-11: checkAll 不发 MQ

**证据**: `CartService.java:247-259` 的 `checkAll` 改了 checked Set 却不发 MQ → MySQL 勾选状态过期。单商品 check 发 CHECK 事件，全选却不发——行为不对称。

**修复方案**: 新增 `CHECK_ALL` 动作类型（避免逐 SKU 发 50 个 MQ 事件的网络浪费）：
```java
sendCartSyncEvent(userId, null, null, toggle ? 1 : 0, "CHECK_ALL");
```
Consumer 侧新增 `handleCheckAll`：批量 UPDATE `SET checked = ? WHERE user_id = ?`（一条 SQL）。若未指定 event.skuId（null），消费者执行全量勾选同步。CHECK 动作含义不变（单个 SKU），CHECK_ALL 新增为全量操作。

### C-12: merge 不发 MQ

**证据**: `CartService.java:437-468` 的 `mergeAnonymousCart` 全程无 `sendCartSyncEvent` → 合并后商品 MySQL 无记录，只能等凌晨对账。

**修复方案**: merge 完成后对每个新增 SKU 发 ADD 事件。

### C-13: 三结构 Key 永不过期 + "7 天过期" 为虚构承诺

**证据**: 全模块 grep `expire|setExpire|ttl` 零命中。`mergeAnonymousCart` 注释（行 420）宣称 "7 天过期自动清除"——没有任何代码实现。

**修复方案**: 每次写操作时 `EXPIRE` 设置为 30 天，或定时任务清理过期购物车。

### C-14: home CartAggService 读 skuName/skuImage 字段名与 cart 返回不匹配

**证据**: `CartAggService.java:184-185` 读 `item.get("skuName")` / `item.get("skuImage")`，但 cart 的 `CartItemVO` 字段名是 `name`（行 22）且 `image` 从未赋值（行 40）。

**修复方案**: 
- **短期**：CartAggService 行 184 `"skuName"` → `"name"`（CartService 已正确填充 name）。`"skuImage"` → `"image"` 保留（图片暂为 null，不影响页面核心功能）
- **长期**：CartService 构建 CartItemVO 时增加 `.image(sku.getImage())`（需 ProductFeignClient.SkuDTO 加 image 字段）

---

## 🟡 低 (11)

| # | 问题 | 位置 | 修复方案 |
|:--:|------|------|------|
| C-15 | 对账单条脏数据中断全任务 | `CartReconcileJob.java:111,140` | `Integer.parseInt` 加 try-catch + log.error 记录失败行的 skuId 和原始 value，单条失败不阻中断后续 |
| C-16 | 对账全量加载 + 50000 静默截断 | `CartReconcileJob.java:73` | 游标分页替代全量加载 |
| C-17 | 凭据明文冗余（Nacos 已存在同值） | `application-datasource.properties` | 加注释说明 Nacos fallback 真实有效 |
| C-18 | ProductFeignClient.getSkuDetail 死代码 | cart/feign/ProductFeignClient.java:26 | 删除未使用的方法和 fallback |
| C-19 | CartItemVO.image 死字段 | `CartItemVO.java:40` | 标注 TODO 或填充 |
| C-20 | Redisson 依赖零使用 | `pom.xml:45-47` | 删除 |
| C-21 | product-api 依赖三重死 | `pom.xml:98-103` | 删除（模块不存在 + 代码零引用 + 注释误导） |
| C-22 | FeignUnifiedConfig 文档夸大 | `FeignUnifiedConfig.java:33` | 修正注释，删除不存在的"认证 Token 注入"声明 |
| C-23 | 空购物车 allChecked 语义不一致 | `CartService.java:309` | 空购物车返回 allChecked=false |
| C-24 | 空 cart 返回 allChecked=true 但含失效商品返回 false | `CartService.java:344-347` | 统一为空购物车 + 全部失效都返回 false |
| C-25 | logback-spring.xml 格式错误 | `logback-spring.xml:24` | `%boldCyan()` 修复 |
| C-26 | application.yml 重复 key + datasource.properties 重复声明 | `application.yml:147` | 删除重复 |

---

## 修复执行计划

| 组 | 包含 | 涉及文件 | 顺序 |
|:--:|------|------|:--:|
| G1 | C-02/C-03/C-04/C-05 竞态&MQ | CartService.java (updateQuantity/merge/clear) + CartSyncConsumer | 1 |
| G2 | C-01/C-15/C-16 对账 | CartReconcileJob.java | 2 |
| G3 | C-06/C-07/C-08 安全边界 | CartController + GatewayAuthFilter + InternalCallFeignConfig | 3 |
| G4 | C-10/C-11/C-12/C-13 业务完整性 | CartService.java (checkAll/merge/TTL) | 4 |
| G5 | C-09/C-14 跨模块 | CartAggService.java(home) | 5 |
| G6 | C-15~C-26 工程清理 | 多文件 | 6 |
