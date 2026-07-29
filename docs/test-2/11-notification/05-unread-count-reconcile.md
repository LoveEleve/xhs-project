# 未读计数与对账机制

> 模块：notification | 核心类：UnreadCountService、UnreadReconcileJob

---

## 1. 业务背景

通知模块需要实时告诉用户"有几条未读通知"，并按类型拆分（点赞 3 条、评论 1 条、关注 0 条）。用 `SELECT COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0` 在技术上可行，但存在两个问题：

1. **高频读取**：用户每次打开 App、下拉刷新、标记已读后——都触发一次 MySQL COUNT 扫描，压力集中在 DB
2. **即时性要求**：用户收到新通知后，红点数字必须 "立即" 更新——不能有缓存延迟

**方案**：Redis 做热计数（O(1) 内存操作），MySQL 做持久化源（对账基准）。两者通过 XXL-Job 每 5 分钟对账保证最终一致。

---

## 2. 架构决策

### 2.1 Redis 数据结构选择

| 选项 | 实现 | 问题 |
|---|---|---|
| String 存 JSON `{total:3, types:{1:2,2:1}}` | 一次 GET | 原子 INCR/DECR 需要完整 JSON 序列化→修改→写回，非原子 |
| **String + Hash**（当前） | `INCR key` + `HINCRBY key field 1` | 两个独立操作，但原子性由 Lua 弥补 |

选择 String + Hash：`INCR` 和 `HINCRBY` 都是 Redis 原子命令，不需要读取当前值再写入。分类查询时 `HGETALL` 返回所有类型计数，比 "GET JSON → 解析 → 找字段" 快。

### 2.2 DECR 为什么需要 Lua？

简单的 `DECR key` 会减到负数——如果两次标记已读同时执行：

```
线程 A: GET key → 1
线程 B: GET key → 1
线程 A: SET key = 0
线程 B: SET key = 0   ← 应该是 -1？不，两次减 1 应该是 -1，但因为并发只减了 1
```

加上 Redis 故障恢复后 Key 丢失，DECR 起始值就是 0，直接变成 -1。

Lua 方案：先检查值 ≤ 0，是则返回 0 不操作。

### 2.3 resetUnreadByType 为什么需要 Lua？

流程需要三步：① 读类型计数 ② 从总计数减掉 ③ 归零类型计数。

三步之间如果有另一个线程也执行 `resetUnreadByType`（不同类型），可能出现 "总未读被多减"。Lua 保证三步原子执行。

### 2.4 全部已读为什么直接 DELETE？

`resetUnread` 直接 `DEL key` 而不是 `SET key=0`。因为已读后不需要保留 0 值——下次 INCR 会自动重建 Key。这比 SET 更快（DELETE 是常数时间，且不需要在后续查询中过滤 0 值）。

---

## 3. 源码追踪

### 3.1 增加未读（UnreadCountService.java:76）

```java
public void incrementUnread(Long userId, Integer type) {
    stringRedisTemplate.opsForValue()
        .increment(UNREAD_TOTAL_KEY + userId);              // INCR notify:unread:{uid}
    stringRedisTemplate.opsForHash()
        .increment(UNREAD_TYPE_KEY + userId,                 // HINCRBY notify:unread:type:{uid} {type} 1
            String.valueOf(type), 1);
}
```

调用时机：`NotificationService.processEvent` 中只有新建通知时调用（聚合通知不调用）。

Redis 的 `INCR` 和 `HINCRBY` 都是**原子操作**——不需要先 GET 再 SET。即使 key 不存在，Redis 也会从 0 开始递增。

### 3.2 减少未读——Lua 防负数（UnreadCountService.java:90）

**SAFE_DECR 脚本**（源码 43-46）：
```lua
local count = redis.call('GET', KEYS[1])
if count == false or tonumber(count) <= 0 then
    return 0              -- 已为 0 或 Key 不存在，不再递减
end
return redis.call('DECR', KEYS[1])
```

**SAFE_HDECR 脚本**（源码 48-51）：
```lua
local count = redis.call('HGET', KEYS[1], ARGV[1])
if count == false or tonumber(count) <= 0 then
    return 0
end
return redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
```

Java 调用方式（源码 90-101）：
```java
public void decrementUnread(Long userId, Integer type) {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>(SAFE_DECR_SCRIPT, Long.class);
    stringRedisTemplate.execute(script,
        Collections.singletonList(UNREAD_TOTAL_KEY + userId));

    if (type != null) {
        DefaultRedisScript<Long> hScript = new DefaultRedisScript<>(SAFE_HDECR_SCRIPT, Long.class);
        stringRedisTemplate.execute(hScript,
            Collections.singletonList(UNREAD_TYPE_KEY + userId),
            String.valueOf(type));
    }
}
```

### 3.3 按类型重置——Lua 原子三步（UnreadCountService.java:115）

**RESET_BY_TYPE 脚本**（源码 62-71）：
```lua
local typeCount = redis.call('HGET', KEYS[2], ARGV[1])
if typeCount == false or tonumber(typeCount) <= 0 then
    return 0                          -- 类型计数已是 0，无需操作
end
local total = redis.call('GET', KEYS[1])
if total == false then total = '0' end
local newTotal = tonumber(total) - tonumber(typeCount)
if newTotal < 0 then newTotal = 0 end -- 防负数兜底
redis.call('SET', KEYS[1], tostring(newTotal))
redis.call('HSET', KEYS[2], ARGV[1], '0')
return tonumber(typeCount)
```

三步：HGET 类型计数 → 从总计数减去 → HSET 归零。Lua 执行期间 Redis 单线程保证不被中断。

### 3.4 获取未读（UnreadCountService.java:125）

```java
public UnreadCountVO getUnreadCount(Long userId) {
    String totalStr = stringRedisTemplate.opsForValue()
        .get("notify:unread:" + userId);
    int total = totalStr != null ? Math.max(0, Integer.parseInt(totalStr)) : 0;

    Map<Object, Object> entries = stringRedisTemplate.opsForHash()
        .entries("notify:unread:type:" + userId);
    Map<Integer, Integer> details = new HashMap<>();
    entries.forEach((k, v) -> {
        int count = Math.max(0, Integer.parseInt(v.toString()));
        if (count > 0) details.put(Integer.parseInt(k.toString()), count);
    });
    return UnreadCountVO.builder().total(total).details(details).build();
}
```

`details` 只包含 count > 0 的类型——值为 0 的 type field 不返回，减少前端解析空白数据。

### 3.5 对账修复——forceSetUnread（UnreadCountService.java:147）

```java
public void forceSetUnread(Long userId, int total, Map<Integer, Integer> typeCountMap) {
    stringRedisTemplate.opsForValue()
        .set("notify:unread:" + userId, String.valueOf(total));
    if (typeCountMap != null && !typeCountMap.isEmpty()) {
        Map<String, String> hashMap = new HashMap<>();
        typeCountMap.forEach((type, count) ->
            hashMap.put(String.valueOf(type), String.valueOf(count)));
        stringRedisTemplate.opsForHash()
            .putAll("notify:unread:type:" + userId, hashMap);
    }
}
```

直接覆盖 SET——不需要计算偏差量（Redis - DB），只需要 DB 的真实值。由 `UnreadReconcileJob` 调用。

### 3.6 对账任务——游标分页扫描（UnreadReconcileJob.java:60）

（以下为简化版，完整源码含游标推进 `lastUserId = Math.max(lastUserId, userId)` 和 `totalChecked` 计数器）

```java
private int[] doReconcile() {
    int batchSize = 500;
    long lastUserId = 0;
    int queryLimit = batchSize * 10;  // SQL LIMIT = 5000

    while (true) {
        // 游标分页：WHERE user_id > lastUserId，不是 OFFSET 分页
        List<Notification> batch = notificationMapper.selectList(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getIsRead, 0)          // 只查未读
                .gt(Notification::getUserId, lastUserId)  // 游标
                .select(Notification::getUserId, Notification::getType)
                .orderByAsc(Notification::getUserId)
                .last("LIMIT " + queryLimit));

        if (batch.isEmpty()) break;

        // 按 userId 聚合统计每种 type 的未读数
        Map<Long, Map<Integer, Integer>> userTypeCountMap = new HashMap<>();
        for (Notification n : batch) {
            userTypeCountMap
                .computeIfAbsent(n.getUserId(), k -> new HashMap<>())
                .merge(n.getType(), 1, Integer::sum);
        }

        // 逐用户对比 Redis → 不一致则修复
        for (Map.Entry<Long, Map<Integer, Integer>> entry : userTypeCountMap.entrySet()) {
            var redisCount = unreadCountService.getUnreadCount(userId);
            if (redisCount.getTotal() != dbTotal) {
                unreadCountService.forceSetUnread(userId, dbTotal, typeCountMap);
            }
        }

        // 退出条件 + 限速
        if (batch.size() < queryLimit) break;
        Thread.sleep(50);  // 批次间隔 50ms
    }
}
```

**关键设计**：

1. **游标分页**（`user_id > lastUserId`）而非 OFFSET：OFFSET 10000 的 `SELECT ... LIMIT 5000 OFFSET 10000` 需要扫描并丢弃前 10000 行。游标分页用 `WHERE user_id > lastUserId` 走索引定位，O(1) 跳到下一页。

2. **退出条件**：`batch.size() < queryLimit`。当返回的记录数 < LIMIT 时，说明已扫描完所有未读记录。这里用**通知条数**而不是**用户数**判断——500 个用户可能有 5000 条通知。

3. **限速 50ms**：每批之间 sleep 50ms，防止 Redis 重启后全量修复时瞬间打满带宽。

4. **XXL-Job 分布式保证**：Admin 只调度一个 Executor 执行——无需额外的 Redisson 分布式锁。

---

## 4. 面试 Q&A

### Q1：如果 Redis 重启导致所有未读计数丢失，怎么恢复？

**答**：分两步：

1. **读操作不受影响**：`getUnreadCount` 中 `totalStr == null` 时返回 0——用户看到的红点暂时消失，但不会报错。
2. **对账自动恢复**：`UnreadReconcileJob` 每 5 分钟扫描 MySQL 中所有 `is_read=0` 的通知，按 userId 聚合统计后覆盖 Redis。Redis 重启后全量 key 丢失，对账在 5 分钟内会将所有用户计数恢复到 DB 值。

**追问**：5 分钟的延迟可以接受吗？

**答**：可以。对账期间用户看到 0 条未读——暂时少了一个红点，无业务中断。5 分钟后自动修复。如果业务要求更快恢复，可以降低 Cron 频率（如每 1 分钟），代价是 MySQL 扫描压力增大。

### Q2：为什么不用 Redis DECR 加一个简单的 if 判断，而要用 Lua？

**答**：因为 if 判断无法原子化。

```java
// 非原子的"安全 DECR"
String val = redis.get(key);
if (val != null && Integer.parseInt(val) > 0) {
    redis.decr(key);  // ← 这里可能出现竞态
}
```

问题：`GET → IF → DECR` 是三个独立操作。T1 GET=1, T2 GET=1, T1 DECR=0, T2 DECR=-1。

Lua 脚本在 Redis 内部执行，"GET→判断→DECR" 被单线程串行化——消除了竞态。

**追问**：如果业务要求极高吞吐（百万 QPS），Lua 脚本会阻塞 Redis 主线程吗？

**答**：会。Lua 在 Redis 单线程中执行，长时间 Lua 会阻塞其他请求。但当前脚本是 O(1) 操作（一次 GET + 一次 DECR，两个 Redis 原生命令），执行时间极短，不影响吞吐。只有 O(N) 的 Lua 脚本（如遍历大 Key）才需要担心。

### Q3：对账为什么要以 DB 为准而不是双向对账？

**答**：MySQL 是**源**，Redis 是**缓存**。缓存可以丢（重启后恢复），源不能丢。

单向修复的方向：`DB未读数 != Redis未读数 → Redis = DB`。不考虑 `DB = Redis` 的方向——因为 DB 的值来自通知的 INSERT，可靠性高于 Redis 的 INCR/DECR。

**追问**：会不会有 DB 写成功但 Redis INCR 失败的场景？

**答**：会。但 Redis INCR 失败后，对账会识别到 `redis=0, db=1`，将 Redis 修复为 1。用户在这 5 分钟内看不到红点——不影响业务，只是延迟了通知。

---

## 5. 生产实验

### 实验：验证对账自动修复

```bash
# 1. 人为制造不一致
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis')
r.set('notify:unread:10001', '999')
"

# 2. 触发对账（通过 XXL-Job API）
curl -s -c /tmp/xxl_cookie http://21.130.247.89:18080/xxl-job-admin/login \
  -d 'userName=admin&password=123456'
JOB_ID=$(curl -s -b /tmp/xxl_cookie \
  "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=5&start=0&length=5" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
curl -s -b /tmp/xxl_cookie \
  -X POST "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger?id=$JOB_ID"

sleep 3

# 3. 检查修复结果
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis')
print(f'After reconcile: {r.get(\"notify:unread:10001\")}')
"
# 预期：恢复为 DB 真实值，不再是 999
```

---

## 6. 发散思考

### 6.1 如果用 Redis Bitmap 做未读计数？

思路：用一个 BITMAP 记录每条通知的已读状态。`BITCOUNT` 统计未读数，`SETBIT` 标记已读。

| 维度 | String + Hash（当前） | Bitmap |
|---|---|---|
| 内存 | userId × sizeof(String+Hash) | userId × (max通知数 / 8) bytes |
| 分类计数 | 天然支持（Hash field） | 需要多个 Bitmap |
| 计数精度 | INCR/DECR 有偏差风险，需对账 | 精确（取决于 SETBIT 是否完整反映所有通知） |
| 最大 offset | 无限制 | 2^32-1 ≈ 42 亿（Redis Bitmap 限制） |

**不适合当前场景的原因**：
1. 用户可能有数十万条历史通知——40000 bytes/user × 100 万用户 = 40GB
2. 分类未读需要每个类型一个 Bitmap（当前 5 种类型 → 5× 内存）
3. "全部已读" 不能用 BITCOUNT 一次性查询——需要遍历 5 个 Bitmap

Bitmap 更适合**固定数量**的标记场景（如签到、活跃用户），不适合**无限增长**的通知场景。

### 6.2 如果对账不用 XXL-Job，用 Redis 的 Keyspace Notifications？

思路：监听 Redis Key 的 DEL/EXPIRE 事件，发现 `notify:unread:*` 被意外删除时自动重建。

| 维度 | XXL-Job（当前） | Keyspace Notifications |
|---|---|---|
| 触发方式 | 定时轮询（5 分钟） | 事件驱动（实时） |
| 覆盖率 | 全量扫描所有用户 | 仅被删除的 Key |
| Redis 开销 | 无（只是读） | CPU 处理事件 + 连接数 |
| 故障容忍 | Redis 重启后仍可对账 | Redis 重启丢失所有事件 |

**风险**：Redis 的 Keyspace Notifications 在生产中通常关闭（`notify-keyspace-events ""`），因为会产生大量事件（高写入场景下每秒数千条），影响主线程性能。定时对账更稳——虽然延迟 5 分钟，但零风险。

---

## 7. 跨文档引用

- 架构文档：`01-notification-module.md` §6.4 未读计数、§6.5 对账机制
- 测试记录：`02-notification-test-record.md` 测试 5（unread-count）、L7 XXL-Job 对账
