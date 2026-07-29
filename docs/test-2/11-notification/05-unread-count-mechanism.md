# 未读计数机制

> 模块：notification | 端口：19013 | 日期：2026-07-29

---

## 1. 业务背景

通知模块需要实时告诉用户"有多少条未读通知"，并按类型展示（点赞 3 条、评论 1 条、关注 0 条）。传统方案是每次查询数据库 `COUNT(*) WHERE is_read=0`，但在高并发场景下性能不可接受。

**核心挑战**：
1. 高并发读写（新人点赞时写，用户打开 App 时读）
2. 不能出现负数（标记已读的并发导致 DECR 过多）
3. Redis 故障恢复后需要与 DB 保持一致

---

## 2. 架构决策

### 决策 1：Redis 计数 vs 实时 MySQL COUNT

| 方案 | 性能 | 一致性 | 复杂度 |
|---|---|---|---|
| **Redis 计数** | O(1) | 最终一致 | 中（需要对账） |
| MySQL COUNT | 索引扫描 | 强一致 | 低 |

**选择 Redis 计数**：未读计数是高频读取场景（每次打开 App、每次下拉刷新都要查），Redis O(1) 性能远优于 MySQL `SELECT COUNT(*)`。

### 决策 2：Redis 数据结构

| 结构 | 总未读 | 分类未读 | 原子操作 |
|---|---|---|---|
| **String + Hash** | `INCR/DECR` | `HINCRBY` | Lua 脚本 |
| 单一 String | 可以 | 需要多次读写 | 不原子 |

**选择 String + Hash**：
- `notify:unread:{userId}` → String 存总未读数（INCR/DECR）
- `notify:unread:type:{userId}` → Hash 存分类未读数（HINCRBY）

---

## 3. 源码追踪

### 3.1 增加未读（incrementUnread）

```java
// 文件：service/UnreadCountService.java:76
public void incrementUnread(Long userId, Integer type) {
    stringRedisTemplate.opsForValue()
            .increment(UNREAD_TOTAL_KEY + userId);   // INCR
    stringRedisTemplate.opsForHash()
            .increment(UNREAD_TYPE_KEY + userId,
                     String.valueOf(type), 1);       // HINCRBY
}
```

**调用时机**：`NotificationService.processEvent()` 中，只有新建通知（非聚合）时调用。

### 3.2 减少未读（decrementUnread）

```java
// 文件：service/UnreadCountService.java:90
public void decrementUnread(Long userId, Integer type) {
    // Lua: DECR 后不小于 0
    DefaultRedisScript<Long> script =
            new DefaultRedisScript<>(SAFE_DECR_SCRIPT, Long.class);
    stringRedisTemplate.execute(script,
            Collections.singletonList(UNREAD_TOTAL_KEY + userId));

    // Lua: HINCRBY 后不小于 0
    if (type != null) {
        DefaultRedisScript<Long> hScript =
                new DefaultRedisScript<>(SAFE_HDECR_SCRIPT, Long.class);
        stringRedisTemplate.execute(hScript,
                Collections.singletonList(UNREAD_TYPE_KEY + userId),
                String.valueOf(type));
    }
}
```

**调用时机**：标记已读（单条/按类型/全部）时调用。

### 3.3 Lua 防负数脚本

```lua
-- SAFE_DECR_SCRIPT
local count = redis.call('GET', KEYS[1])
if count == false or tonumber(count) <= 0 then
    return 0    -- 已为 0 或 Key 不存在，不再递减
end
return redis.call('DECR', KEYS[1])
```

**为什么需要防负数？**
1. 并发标记已读：线程 A 标记已读 → DECR → 1→0；线程 B 同时标记已读 → DECR → -1
2. Redis 故障恢复后 Key 丢失 → INCR 从 0 开始 → DECR 后变为负数
3. 对账延迟：Redis 计数与 DB 不一致时可能多减

### 3.4 按类型重置（resetUnreadByType）

```lua
-- RESET_BY_TYPE_SCRIPT（原子操作）
local typeCount = redis.call('HGET', KEYS[2], ARGV[1])
if typeCount == false or tonumber(typeCount) <= 0 then
    return 0
end

local total = redis.call('GET', KEYS[1])
if total == false then total = '0' end

local newTotal = tonumber(total) - tonumber(typeCount)
if newTotal < 0 then newTotal = 0 end

redis.call('SET', KEYS[1], tostring(newTotal))  -- 更新总未读
redis.call('HSET', KEYS[2], ARGV[1], '0')       -- 清零类型未读
return tonumber(typeCount)
```

**为什么用 Lua？** 非原子实现分三步：`HGET → DECRBY → HSET`，三步之间有并发窗口。如果两个线程同时执行 `resetUnreadByType`，可能导致总未读被多减。Lua 保证原子性。

### 3.5 全部已读（resetUnread）

```java
// 文件：service/UnreadCountService.java:107
public void resetUnread(Long userId) {
    stringRedisTemplate.delete(UNREAD_TOTAL_KEY + userId);
    stringRedisTemplate.delete(UNREAD_TYPE_KEY + userId);
}
```

直接删除 Key——已读后计数归零，不需要保留。

### 3.6 对账修复（forceSetUnread）

```java
// 文件：service/UnreadCountService.java:147
public void forceSetUnread(Long userId, int total,
                           Map<Integer, Integer> typeCountMap) {
    stringRedisTemplate.opsForValue()
            .set(UNREAD_TOTAL_KEY + userId, String.valueOf(total));
    if (typeCountMap != null && !typeCountMap.isEmpty()) {
        Map<String, String> hashMap = new HashMap<>();
        typeCountMap.forEach((type, count) ->
                hashMap.put(String.valueOf(type), String.valueOf(count)));
        stringRedisTemplate.opsForHash()
                .putAll(UNREAD_TYPE_KEY + userId, hashMap);
    }
}
```

由 `UnreadReconcileJob`（XXL-Job 每 5 分钟）调用，直接覆盖 Redis 为 DB 真实值。

---

## 4. 对账机制（UnreadReconcileJob）

### 4.1 为什么需要对账？

1. **Redis 重启**：所有 Key 丢失，计数归零
2. **主从切换**：INCR/DECR 操作可能在切换时丢失
3. **并发标记已读**：极端情况下 DECR 可能漏执行
4. **系统异常**：INCR 成功但 DB 写入失败（计数偏高）

### 4.2 对账流程

```java
// 文件：job/UnreadReconcileJob.java:47
@XxlJob("unreadReconcileJob")
public void reconcile() {
    // 1. 游标分页扫描 MySQL 未读通知
    //    SELECT user_id, type FROM t_notification
    //    WHERE is_read=0 AND user_id > lastUserId
    //    ORDER BY user_id LIMIT 5000
    //
    // 2. 按 userId 聚合统计每种 type 的数量
    //
    // 3. 对比 Redis 未读计数
    //    不一致 → forceSetUnread(userId, dbTotal, typeCountMap)
    //
    // 4. 批次间限速 50ms，避免 Redis 重启后瞬间打满
}
```

**关键设计**：
- 游标分页（`user_id > lastUserId`）避免 offset 分页的性能问题
- 以 DB 为准修复 Redis——DB 是源，Redis 是缓存
- 退出条件：`batch.size() < LIMIT`（SQL LIMIT = 5000）

---

## 5. 面试 Q&A

### Q1: 为什么不用 MySQL 的 `COUNT(*)` 做实时查询？

**A**: 性能和架构两方面：
1. **性能**：`SELECT COUNT(*) WHERE is_read=0 AND user_id=X` 需要索引扫描，高并发下来回影响其他操作
2. **架构**：notification 是独立服务，API 响应应在 10ms 内。DB 查询延迟 + 网络延迟 > Redis O(1) 内存操作

### Q2: 对账期间的"短暂不一致"是否可以接受？

**A**: 可以接受。
1. 不一致窗口：≤ 5 分钟（对账间隔）
2. 影响：用户看到的未读红点数字可能有 ±1 的误差
3. 最终一致：对账完成后修复为 DB 真实值

### Q3: 如果 Redis 重启导致 10 万用户的未读计数丢失，对账怎么处理？

**A**: 
1. 游标分页 + 限速（50ms/批）保证不会瞬间打满 Redis
2. 每批 500 条通知 → 约 100-500 个用户
3. 10 万用户 × 50ms = 实际修复时间取决于数据分布
4. 对账 Job 可多次执行，逐渐追平

### Q4: `forceSetUnread` 为什么不直接用 INCR/DECR 来修正偏差？

**A**: 修正偏差可以通过 INCR/DECR 差异量，但 `forceSet` 直接覆盖更简单可靠：
- 不需要计算差异量
- 避免 INCR/DECR 的并发问题
- 一次操作（PUTPUT）更高效

---

## 6. 生产实验

### 实验 1：验证 DECR 防负数

```bash
# 1. 重置 Redis 计数
redis-cli DEL notify:unread:10001

# 2. 直接 DECR（防负数机制触发）
# 正常返回 0，不会变为 -1
```

### 实验 2：验证对账修复

```bash
# 1. 人为制造不一致
redis-cli SET notify:unread:10001 999

# 2. 触发对账（手动执行 XXL-Job Handler）

# 3. 验证 Redis 被修复为 DB 真实值
redis-cli GET notify:unread:10001
# 预期：与 DB COUNT 一致
```

---

## 7. 发散章节

### 7.1 如果用户规模达到百万级别，对账性能瓶颈在哪？

1. MySQL 扫描：5 分钟扫描百万行（即使只查 user_id/type 两列）需要分片
2. Redis 覆盖：forceSet 操作的单次量可能很大——考虑用 Pipeline 批处理
3. 优化：引入用户活跃度分级——活跃用户每 5 分钟对账，不活跃用户每 30 分钟对账

### 7.2 有没有更高效的未读计数方案？

**Redis Bitmap**：用 BIT 标记每个通知的已读状态，`BITCOUNT` 统计未读数。

优点：不需要 INCR/DECR，不存在溢出问题
缺点：内存占用大（10 万条通知/用户），无法按类型分类

**权衡**：当前方案（String + Hash）在内存和分类支持之间取得平衡，适合社交通知场景。
