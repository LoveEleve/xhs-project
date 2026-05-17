# 购物车模块 Code Review（第二轮独立审视）

> 模块：my-xhs-cart | 端口：9008 | 评审时间：2026-05-14
> **本轮原则**：不参考任何已有模块方案，从第一性原理独立审视每一个设计决策

---

## 📊 一、评分表

| 维度 | 评分 | 说明 |
|------|:----:|------|
| 架构设计 | ⭐⭐⭐⭐⭐ | Redis 三结构协同，职责分离清晰 |
| 原子性保证 | ⭐⭐⭐⭐⭐ | Lua 脚本保证加购/删除原子性 |
| 参数校验 | ⭐⭐⭐⭐⭐ | 入口校验 + Lua 兜底双重保护 |
| 幂等设计 | ⭐⭐⭐⭐⭐ | Consumer 利用唯一索引实现真正 UPSERT |
| 容错降级 | ⭐⭐⭐⭐⭐ | Feign 降级、MQ 失败不阻塞主流程 |
| 数据一致性 | ⭐⭐⭐⭐⭐ | MQ 异步落库 + 对账修复主动补录 |
| 配置安全 | ⭐⭐⭐⭐⭐ | 移除不适用的逻辑删除配置 |
| 代码质量 | ⭐⭐⭐⭐ | 注释详尽，但部分方法较长可进一步拆分 |
| **综合** | **9.4/10** | |

---

## 🔍 二、本轮独立发现的问题（不参考已有模块）

### 问题 1（P0）：CartAddRequest 缺少 quantity 上限校验

**独立发现过程**：审视 `CartAddRequest` 时，发现只有 `@Min(1)` 没有 `@Max`。用户可以传入 `quantity=Integer.MAX_VALUE`。虽然 Lua 脚本会截断到 99，但 `HINCRBY` 的中间值可能是一个巨大的数字（比如已有 5 个，加 2147483647 = 2147483652），然后再被 HSET 截断为 99。这个中间值虽然存在时间极短，但在 Lua 脚本内部是可见的。

**更关键的问题**：如果未来有人修改了 Lua 脚本去掉截断逻辑，或者绕过 Lua 直接调用 Service 方法，就会出现数量溢出。**防御性编程的原则是：在入口处就拦截非法输入，不要依赖下游兜底。**

**修复**：
```java
// 修复前
@Min(value = 1, message = "数量至少为1")
private Integer quantity = 1;

// 修复后
@Min(value = 1, message = "数量至少为1")
@Max(value = 99, message = "单次加购数量上限为99")
private Integer quantity = 1;
```

**设计原则**：入口校验 + Lua 兜底 = 双重保护。即使 Lua 脚本被修改或绕过，入口校验仍然有效。

---

### 问题 2（P0）：Consumer UPSERT 实现有并发安全问题

**独立发现过程**：注释声称"UPSERT 幂等"，但实际代码是 `select → if null → insert / else → update`。这是经典的 **check-then-act** 反模式。

**风险场景**：
```
时间线（RocketMQ 并发消费同一用户的两条消息）：
T1: 线程A → selectOne(userId=1, skuId=100) → null
T2: 线程B → selectOne(userId=1, skuId=100) → null
T3: 线程A → insert(userId=1, skuId=100, qty=2) → 成功
T4: 线程B → insert(userId=1, skuId=100, qty=3) → DuplicateKeyException！
```

虽然 `t_cart_item` 有唯一索引 `uk_user_sku(user_id, sku_id)` 会阻止重复插入，但异常会导致 RocketMQ 重试，浪费资源。

**修复**：改为 **insert-first** 策略——先尝试 INSERT，catch `DuplicateKeyException` 后转为 UPDATE。利用 MySQL 唯一索引作为并发控制手段，而不是应用层的 select 检查。

```java
// 修复后
private void upsertCartItem(CartSyncEvent event) {
    try {
        // 先尝试 INSERT
        cartItemMapper.insert(item);
    } catch (DuplicateKeyException e) {
        // 唯一索引冲突 → 转为 UPDATE
        CartItem existing = cartItemMapper.selectOne(...);
        if (existing != null) {
            existing.setQuantity(event.getQuantity());
            cartItemMapper.updateById(existing);
        }
    }
}
```

**为什么 insert-first 比 select-first 好？**
| 维度 | select-first（原方案） | insert-first（修复后） |
|------|---------------------|---------------------|
| 并发安全 | ❌ 两线程同时 select 为空 | ✅ 唯一索引保证只有一个成功 |
| 正常路径性能 | 1 次 SELECT + 1 次 INSERT | 1 次 INSERT（更快） |
| 冲突路径性能 | 异常 + 重试 | 1 次 INSERT(失败) + 1 次 SELECT + 1 次 UPDATE |
| 代码意图 | 隐式依赖唯一索引 | 显式利用唯一索引 |

---

### 问题 3（P1）：全选操作 checked Set 可能有脏数据

**独立发现过程**：审视 `checkAll(true)` 的逻辑——获取 Hash 所有 key，SADD 到 checked Set。但如果之前有商品被删除时 Lua 脚本执行了 HDEL + SREM + ZREM，而在极端情况下（比如 Redis 主从切换丢失了 SREM 命令），checked Set 中可能残留已删除商品的 skuId。

此时执行全选（SADD），不会清除这些残留的 skuId。结果：checked Set 比 items Hash 多出幽灵成员。

**修复**：全选时先 DEL 旧 Set，再 SADD 新成员。

```java
// 修复前
stringRedisTemplate.opsForSet().add(checkedKey, skuIdArray);

// 修复后
stringRedisTemplate.delete(checkedKey);  // 先清除可能的脏数据
stringRedisTemplate.opsForSet().add(checkedKey, skuIdArray);
```

---

### 问题 4（P1）：getCartList 中 builder.build() 被调用两次

**独立发现过程**：审视金额计算逻辑时发现：

```java
if (isChecked && sku.getPrice() != null && Boolean.TRUE.equals(builder.build().getValid())) {
```

`builder.build()` 会创建一个新的 `CartItemVO` 对象，仅仅为了检查 `valid` 字段。而后面还有 `cartItems.add(builder.build())`，又创建了一个。每个商品创建了 2 个对象，50 个商品就是 100 个对象。

更重要的是，这种写法**语义不清晰**——读代码的人需要理解 `builder.build()` 返回的是一个临时对象，而不是最终对象。

**修复**：用一个 `boolean valid` 局部变量替代。

```java
// 修复后
boolean valid = false;
if (sku.getStatus() != null && sku.getStatus() == 1 && ...) {
    builder.valid(true);
    valid = true;
}
if (isChecked && valid && sku.getPrice() != null) {
    checkedAmount = ...;
}
```

---

### 问题 5（P1）：对账任务场景 1 只记日志不修复

**独立发现过程**：对账任务注释说"Redis 有 + MySQL 无 → 记录日志，等 MQ 重试"。但对账任务在凌晨 4 点执行，此时 MQ 消息如果还没消费，说明消息已经丢失（RocketMQ 默认重试 16 次，每次间隔递增，最长 2 小时，16 次重试总共约 4.5 小时）。

凌晨 4 点执行对账，距离用户操作至少过了几个小时。如果此时 MySQL 还没有记录，基本可以确认 MQ 消息已丢失，应该**主动补录**而不是等待一个永远不会来的重试。

**修复**：场景 1 改为主动 INSERT，利用唯一索引防止重复。

---

### 问题 6（P1）：对账任务场景 2 日志打印错误

**独立发现过程**：

```java
mysqlItem.setQuantity(redisQty);  // 先修改了 quantity
log.info("修复数量: mysql={} → redis={}", mysqlItem.getQuantity(), redisQty);
// 此时 mysqlItem.getQuantity() 已经是 redisQty 了，日志打印的两个值相同！
```

**修复**：先保存旧值，再修改，日志打印旧值和新值。

同时发现场景 2 只比较了数量，没有比较选中状态。如果 Redis 中选中状态和 MySQL 不一致，也应该修复。

---

### 问题 7（P2）：application.yml 配置了不适用的逻辑删除

**独立发现过程**：`application.yml` 中配置了：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
```

但 `t_cart_item` 表没有 `deleted` 字段，`CartItem` 实体也没有 `@TableLogic` 注解。虽然 MyBatis-Plus 的逻辑删除需要 `@TableLogic` 注解才会生效，全局配置只是声明字段名，但这个配置是**误导性的**——未来有人看到这个配置可能会以为购物车表支持逻辑删除，或者在 CartItem 上加 `@TableLogic` 注解导致查询异常（因为表里没有 deleted 列）。

**修复**：移除不适用的逻辑删除配置，加注释说明原因。

---

## ✅ 三、修复记录

| # | 级别 | 问题 | 修复方案 | 文件 | 状态 |
|---|------|------|----------|------|:----:|
| 1 | **P0** | CartAddRequest 缺少 quantity 上限 | 添加 `@Max(99)` | CartAddRequest.java | ✅ |
| 2 | **P0** | Consumer UPSERT 并发不安全 | insert-first + catch DuplicateKey | CartSyncConsumer.java | ✅ |
| 3 | **P1** | 全选 checked Set 可能有脏数据 | 先 DEL 再 SADD | CartService.java | ✅ |
| 4 | **P1** | builder.build() 调用两次浪费 | 用 boolean 局部变量替代 | CartService.java | ✅ |
| 5 | **P1** | 对账场景 1 只记日志不修复 | 改为主动 INSERT 补录 | CartReconcileJob.java | ✅ |
| 6 | **P1** | 对账场景 2 日志打印错误 + 只比较数量 | 保存旧值 + 同时比较选中状态 | CartReconcileJob.java | ✅ |
| 7 | **P2** | 逻辑删除配置不适用 | 移除配置 + 加注释 | application.yml | ✅ |

---

## 🧠 四、独立思考：质疑之前的设计决策

### 4.1 Lua 脚本真的有必要吗？

**之前的理由**："对标社交服务的 follow_and_count.lua"。

**独立思考**：不应该因为"别的模块用了 Lua"就用 Lua。要从购物车自身的需求出发判断。

**结论：加入购物车确实需要 Lua，但理由不是"对标社交服务"。**

真正的理由是：购物车有 **50 品上限** 这个业务约束。如果"检查上限"和"加入"不是原子的，并发请求可以同时通过检查，都执行加入，突破上限。这是一个**业务正确性问题**，不是性能问题。

```
反例：如果购物车没有上限限制，那 HINCRBY + SADD + ZADD 三个命令即使非原子执行，
最终状态也是正确的（HINCRBY 幂等累加，SADD 幂等添加，ZADD NX 幂等）。
此时 Lua 脚本只是减少网络往返的性能优化，不是必须的。
```

**删除操作也确实需要 Lua**：三结构必须同时删除，否则会出现 checked Set 中有 items Hash 中不存在的 skuId（幽灵成员），导致全选判断逻辑错误。

### 4.2 Pipeline 真的有必要吗？

**之前的理由**："减少 3 次 RTT 为 1 次"。

**独立思考**：购物车列表查询的 QPS 通常不高（用户打开购物车页面才触发），3 次 Redis 命令的延迟约 3ms，Pipeline 优化到 1ms。这 2ms 的差距对用户体验几乎没有影响。

**结论：Pipeline 是一个合理的优化，但不是必须的。** 它的真正价值不在于省 2ms，而在于：
1. **代码意图更清晰**：一次 Pipeline 表达了"这三个查询是一组"的语义
2. **减少 Redis 连接占用**：高并发时减少连接池压力
3. **养成好习惯**：在更高 QPS 的场景（如首页推荐）中，Pipeline 是必须的

### 4.3 对账修复的方向对吗？

**之前的理由**："对标计数服务的三层保障"。

**独立思考**：对账修复以 Redis 为准修复 MySQL，这在 Redis 正常运行时是对的。但如果 Redis 数据丢失（主从切换/重启），应该反过来以 MySQL 为准恢复 Redis。

**当前代码的问题**：对账任务只扫描 MySQL 中有记录的用户。如果一个用户的购物车只在 Redis 中（MQ 消息全部丢失），对账任务根本扫描不到这个用户。

**修复方案**（本轮已修复场景 1）：对账任务场景 1 改为主动 INSERT 补录。但更完善的方案是：
1. 正向对账：扫描 MySQL 用户 → 与 Redis 对比（当前实现）
2. 反向对账：扫描 Redis 用户 → 与 MySQL 对比（需要 SCAN 命令遍历 Redis Key）

当前只实现了正向对账 + 场景 1 补录，已经覆盖了大部分情况。反向对账作为后续优化。

### 4.4 Redis Key 没有设置过期时间，会不会内存泄漏？

**独立发现**：所有购物车 Redis Key 都没有设置 TTL。如果用户注销账号或长期不活跃，购物车数据会永久占用 Redis 内存。

**分析**：
- 购物车数据量：每个用户最多 50 个 skuId，3 个 Key（Hash + Set + ZSet）
- 单用户内存占用：约 5KB
- 100 万用户：约 5GB

**结论**：当前阶段不设置 TTL 是合理的——购物车数据是用户资产，不应该因为过期而丢失。但需要配合以下机制：
1. 用户注销时主动清除购物车数据
2. 定期清理长期不活跃用户的购物车（如 1 年未登录）

这属于后续优化，不是当前的 bug。

---

## 🎤 五、面试话术（独立思考版）

### Q1: 加入购物车为什么要用 Lua 脚本？

> **A**: "不是因为别的模块用了 Lua 我就用。购物车有一个关键的业务约束——50 品上限。加入购物车需要先检查当前数量是否已满，再执行加入。如果这两步不是原子的，并发请求可以同时通过检查，都执行加入，突破上限。这是一个业务正确性问题，不是性能问题。
>
> Lua 脚本在 Redis 单线程中执行，天然保证'检查 + 加入'的原子性。同时我还把截断上限、默认选中、记录排序都放在同一个 Lua 脚本中，一次网络往返完成所有操作。
>
> 但如果购物车没有上限限制，其实不需要 Lua——HINCRBY、SADD、ZADD 都是幂等操作，非原子执行最终状态也是正确的。"

### Q2: Consumer 的 UPSERT 怎么保证幂等？

> **A**: "我用的是 insert-first 策略——先尝试 INSERT，如果唯一索引冲突（DuplicateKeyException）就转为 UPDATE。
>
> 为什么不用 select-first？因为 select + insert 两步不是原子的。RocketMQ 并发消费时，两个线程可能同时 select 为空，然后都执行 insert，导致唯一索引冲突异常。虽然异常不会导致数据错误（唯一索引保证了），但会触发 RocketMQ 重试，浪费资源。
>
> insert-first 策略利用 MySQL 唯一索引作为并发控制手段，比应用层的 select 检查更可靠。正常路径只需要 1 次 INSERT，性能也更好。"

### Q3: 对账修复为什么要主动补录？

> **A**: "对账任务在凌晨 4 点执行。RocketMQ 默认重试 16 次，最长间隔 2 小时，16 次重试总共约 4.5 小时。如果凌晨 4 点 MySQL 还没有记录，说明 MQ 消息已经丢失，不会再有重试了。
>
> 之前的方案是'只记日志等 MQ 重试'，这是错误的——等待一个永远不会来的重试。正确的做法是主动 INSERT 补录，利用唯一索引防止重复。"

### Q4: 全选操作为什么要先 DEL 再 SADD？

> **A**: "直接 SADD 只会往 Set 里添加成员，不会删除已有成员。如果 checked Set 中有残留的脏数据（比如之前删除商品时 SREM 因为 Redis 主从切换丢失了），直接 SADD 不会清除这些残留。
>
> 先 DEL 整个 Set 再 SADD 所有当前商品，保证 checked Set 的内容和 items Hash 完全一致。DEL 是 O(1) 操作，不影响性能。"

### Q5: 为什么入口校验和 Lua 脚本都做了数量上限检查？

> **A**: "防御性编程原则：在入口处拦截非法输入，不要依赖下游兜底。
>
> `@Max(99)` 在 Controller 层拦截，Lua 脚本中的截断是兜底保护。两层防御的好处：
> 1. 如果未来有人修改了 Lua 脚本去掉截断逻辑，入口校验仍然有效
> 2. 如果有人绕过 Controller 直接调用 Service 方法，Lua 脚本仍然保护
> 3. 入口校验返回友好的错误信息，Lua 截断是静默处理
>
> 这不是重复，而是不同层次的防御。"

---

## 📁 六、最终文件清单

```
my-xhs-cart/src/main/java/com/myxhs/cart/
├── CartApplication.java              # 启动类
├── config/
│   └── RedisScriptConfig.java        # Lua 脚本预加载
├── controller/
│   └── CartController.java           # REST 接口（8个端点）
├── service/
│   └── CartService.java              # 核心业务（Lua 原子操作 + Pipeline）
├── consumer/
│   └── CartSyncConsumer.java         # MQ 消费者（insert-first UPSERT）
├── job/
│   └── CartReconcileJob.java         # 对账修复（主动补录）
├── feign/
│   ├── ProductFeignClient.java       # 商品服务 Feign
│   └── ProductFeignFallbackFactory.java  # Feign 降级
├── entity/
│   └── CartItem.java                 # 实体（物理删除，无 deleted 字段）
├── mapper/
│   └── CartItemMapper.java           # Mapper
├── dto/
│   ├── request/
│   │   ├── CartAddRequest.java       # 加购请求（@Max(99) 入口校验）
│   │   ├── CartUpdateQuantityRequest.java
│   │   ├── CartCheckRequest.java
│   │   └── CartMergeRequest.java
│   ├── response/
│   │   ├── CartListVO.java
│   │   └── CartItemVO.java
│   └── event/
│       └── CartSyncEvent.java

my-xhs-cart/src/main/resources/
├── application.yml                   # 已移除不适用的逻辑删除配置
└── lua/
    ├── cart_add.lua                  # 加购原子操作
    └── cart_remove.lua               # 删除原子操作
```

---

## 🧪 七、测试验证

| 测试场景 | 预期 | 实际 | 通过 |
|----------|------|------|:----:|
| 加入购物车 | 200 OK | ✅ | ✅ |
| 同一商品累加 | qty=5 | ✅ | ✅ |
| quantity=100（新增 @Max 校验） | 400 "单次加购数量上限为99" | ✅ code=40002 | ✅ |
| 修改数量 | 200 OK | ✅ | ✅ |
| 不存在商品修改 | "购物车商品不存在" | ✅ code=30006 | ✅ |
| 删除商品（Lua 原子） | 三结构同时删除 | ✅ | ✅ |
| 取消勾选 | Set 移除 | ✅ | ✅ |
| 全选（先DEL再SADD） | Set 干净重建 | ✅ | ✅ |
| 购物车列表（Pipeline） | 数据正确 | ✅ | ✅ |
| Feign 降级 | 标记"商品信息获取失败" | ✅ | ✅ |
