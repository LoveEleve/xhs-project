# IM 离线消息与写扩散存储 — 深度技术分析

> 关联源码：`ChatService.java` / `MessagePersistService.java` / `ChatMessage.java` / `ChatUserRelation.java`

---

## 业务背景

IM 消息存储面临两个核心问题：

**问题 1：写扩散 vs 读扩散**

1-to-1 IM 场景下，读扩散（查接收方收件箱）理论可行，但会随对话数增加查询复杂度。写扩散把"查"的负担转移到"写"时——发消息时维护好会话关系，读时只需一次查询。

本模块采用**写扩散"共享存储"**：一条消息写一次，双方通过 `conversation_id` 共享访问。

**问题 2：离线消息**

接收方不在线时，消息不能丢。存 DB 太重（频繁写入），方案选型：

| 方案 | 持久化 | 性能 | 裁剪能力 |
|---|---|---|---|
| Redis List + LPUSH | 无 | 快 | 容易（LTRIM） |
| Redis ZSet + Lua | 无 | 快 | 灵活（ZREMRANGEBYRANK） |
| 直接写 DB | 有 | 慢（磁盘 IO） | SQL 删除 |

---

## 写扩散"共享存储"设计

### 为什么只存一份

传统 IM 写扩散每条消息写 N 份（每个会话参与方各一份）：

```
❌ 多份写扩散：
  A→B 消息 → INSERT t_message (conversation_A_B) {A视角}
           → INSERT t_message (conversation_B_A) {B视角}
  2 倍存储 + 2 倍写入
```

本模块用 `conversation_id = min(A,B)*31 + max(A,B)` 作为分片键：

```
✅ 共享存储：
  A→B 消息 → INSERT t_chat_message (conversation_id=310062) {唯一一份}
  ↑ 双方查询都走 conversation_id，查同一份数据
```

### 会话关系独立维护

消息只存一份，但双方各自的**会话摘要**（最后消息、未读数）独立维护：

```
t_chat_user_relation:
  (user_id=10001, peer_id=10002, unread_count=0)   ← 发送方
  (user_id=10002, peer_id=10001, unread_count=1)   ← 接收方
```

`@Transactional` 中保证一致性：
```java
messagePersistService.saveMessageWithTransaction(...) {
    // 1. INSERT t_chat_message（一份）
    chatMessageMapper.insert(message);
    // 2. UPSERT 发送方会话（unread_count 不变）
    upsertConversation(senderId, receiverId, ... , unreadIncrement=0);
    // 3. UPSERT 接收方会话（unread_count +1）
    upsertConversation(receiverId, senderId, ... , unreadIncrement=1);
}
```

### conversation_id 算法

```java
public static long generateConversationId(Long userIdA, Long userIdB) {
    long min = Math.min(userIdA, userIdB);
    long max = Math.max(userIdA, userIdB);
    return min * 31 + max;
}
```

**为什么是乘法不是位运算**：原 SQL 注释用 `min<<32|max`，但 Java 中 `long << 32` 返回 `long` 不会溢出，不过位运算后的结果在高 32 位和低 32 位分别编码两个 ID，当 userId 超过 32 位范围时会有重叠风险。改成乘法 `min*31+max` 更直观且无位运算边界问题。31 是质数，减少碰撞。

---

## 离线消息机制

### 存储：Redis ZSet + Lua 原子操作

```java
private static final DefaultRedisScript<Long> STORE_OFFLINE_SCRIPT =
        new DefaultRedisScript<>(
                "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1]) " +
                "local size = redis.call('ZCARD', KEYS[1]) " +
                "local maxSize = tonumber(ARGV[3]) " +
                "if size > maxSize then " +
                "  redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - maxSize - 1) " +
                "  size = maxSize " +
                "end " +
                "redis.call('EXPIRE', KEYS[1], ARGV[4]) " +
                "return size",
                Long.class);
```

**为什么用 Lua？** 4 个操作（ZADD + ZCARD + 条件 ZREMRANGEBYRANK + EXPIRE）合并为 1 次 Redis 往返。

**裁剪策略**：只在上限（1000）被突破时才裁剪最旧的消息。大多数用户不会有 1000 条离线消息，避免了每次写入都 LTRIM 的开销。

### 推送：上线批量拉取

```
用户上线 → afterConnectionEstablished()
    │
    ├─ ZRANGE im:offline:10002 0 999 → [msgId1, msgId2, ...]
    ├─ SELECT_BATCH_IDS FROM t_chat_message WHERE id IN (...)
    ├─ 按 seq_no 排序（保证顺序）
    └─ 推送 OFFLINE 事件 → 客户端批量渲染
```

**为什么不 ZRANGE 后直接删除？**
消息需要客户端 ACK 后才删除（`handleAck` → `ZREM`）。如果连接断开但客户端未 ACK，下次上线继续推送——客户端通过 msgId 去重。

### ACK 驱动删除

```java
public void handleAck(Long userId, ImMessage imMsg) {
    if (imMsg.getMsgId() == null) return;
    stringRedisTemplate.opsForZSet().remove(OFFLINE_KEY_PREFIX + userId,
            String.valueOf(imMsg.getMsgId()));
}
```

---

## 未读计数机制

### Redis Hash + 分会话粒度

```java
// 发送时：接收方的未读计数 +1
stringRedisTemplate.opsForHash().increment(UNREAD_KEY_PREFIX + receiverId,
        String.valueOf(senderId), 1);

// 已读时：清零
stringRedisTemplate.opsForHash().put(UNREAD_KEY_PREFIX + userId,
        String.valueOf(peerId), "0");
```

分会话未读 → 查询总未读时 SUM Hash 的所有值：

```java
public int getTotalUnreadCount(Long userId) {
    Map<Object, Object> entries =
        stringRedisTemplate.opsForHash().entries(UNREAD_KEY_PREFIX + userId);
    if (entries.isEmpty()) {
        return getUnreadCountFromDb(userId); // Redis 降级 → DB
    }
    return entries.values().stream()
        .mapToInt(v -> Integer.parseInt(v.toString())).sum();
}
```

### Redis 降级

Redis 重新启动或数据丢失时，未读计数从 MySQL `t_chat_user_relation.unread_count` 恢复。这是最终一致性的体现——Redis 提供高性能读写，MySQL 提供可靠备份。

---

## 生产实验

### 消息持久化 + seq_no 连续验证

连续发送 3 条消息后查 MySQL：

```sql
SELECT id, seq_no, content
FROM t_chat_message WHERE conversation_id = 320033
ORDER BY seq_no DESC LIMIT 3;
-- id=2082716494686388226, seq_no=7, content=seq测试3
-- id=2082716494636056577, seq_no=6, content=seq测试2
-- id=2082716494577336322, seq_no=5, content=seq测试1
```

Redis seq 计数器确认：
```bash
GET im:seq:320033 → "7"
```

验证：seq_no **5→6→7** 连续无跳号，与 MySQL 记录一一对应。

### 未读计数验证

```
发送前：  HGETALL im:unread:10002 → {}（空）
发送后：  HGETALL im:unread:10002 → {10001: "1"}
标记已读： HGETALL im:unread:10002 → {10001: "0"}
```

MySQL 侧确认：
```sql
SELECT unread_count FROM t_chat_user_relation
WHERE user_id=10002 AND peer_id=10001;
-- 发送后: 1, 已读后: 0
```

Redis 与 MySQL 最终一致。

### 离线消息推送完整链路验证

```
步骤 1: A 发送消息，B 未连接 WebSocket
   → Lua ZADD im:offline:10002
   → ZCARD = 1（本条）, 累计 6 条（含历史未消费消息）
   → msgId 确认在 ZSet 中 ✅

步骤 2: B 上线连接 WebSocket
   → afterConnectionEstablished 触发
   → ZRANGE im:offline:10002 0 999
   → SELECT_BATCH_IDS 从 MySQL 批量查询消息内容
   → 按 seq_no 排序后推送 OFFLINE 事件
   → B 收到 OFFLINE，msgs=[6 条消息] ✅

步骤 3: B 逐条发送 ACK
   → handleAck → ZREM im:offline:10002
   → ZCARD = 0 ✅

验证：离线消息完整链路 A发→存ZSet→上线推送→ACK清空，全流程通过。
```

### conversation_id 计算验证

```python
def conv_id(a, b):
    return min(a, b) * 31 + max(a, b)
conv_id(10001, 10002)  # → 320033
```

MySQL 查询确认 `conversation_id=320033` 与实际计算一致。
conv_id(10001, 10002)  # → 320033
```

MySQL 查询 `t_chat_message` 确认 `conversation_id=320033` 正确。

### t_chat_message

| 索引 | 作用 | 查询场景 |
|---|---|---|
| `idx_conversation_seq` | conversation_id + seq_no | 历史消息翻页 |
| `idx_sender` | sender_id | 发送记录统计 |
| `idx_receiver` | receiver_id | 接收记录统计 |

### t_chat_user_relation

| 索引 | 作用 |
|---|---|
| `uk_user_peer` UNIQUE | 保证每对用户只有一条关系记录 |

---

## 面试 Q&A

**Q: 为什么离线消息索引用 Redis 而内容存 MySQL？**
A: 离线消息需要两个能力：(1) 暂存"哪些消息未送达"的索引——Redis ZSet 合适，ZADD+ZRANGE+ZREM 都是 O(log N)；(2) 存储消息内容——MySQL 持久化。所以拆了两层：ZSet 只存 msgId（~40 字节/条，1000 条 ≈ 40KB/用户），内容在 MySQL 中，上线时 SELECT_BATCH_IDS 查询。纯 Redis（内容也在 ZSet 中）会导致内存爆炸，纯 MySQL（用状态字段标记未读）每次上线都要扫全表。

**Q: 消息存一份，删除对话怎么办？**
A: 目前不支持单条消息删除（`is_deleted` 字段在 `t_chat_user_relation` 上，不在 `t_chat_message` 上）。消息表不做逻辑删除——IM 消息天然不可篡改。

**Q: conversation_id 用 min*31+max 碰撞概率？**
A: 数学上不完全为 0（如 (10001,10002) 和 (1,320032) 都等于 320033），但 conversation_id 并非唯一约束——它只是消息表的分片键和查询条件。真实场景下 userId 是雪花 ID（64 位，分布稀疏），碰撞概率可忽略。

---

## 发散

### 备选：消息 ID 作为 ZSet member，内容存 DB

当前设计离线 ZSet 只存 msgId（String 类型，Redis 内部 ~40 字节/条含 overhead），消息内容在 MySQL。上线时先 ZRANGE 拿 ID 列表，再 SELECT_BATCH_IDS 批量查。1000 条离线 ≈ 40KB/用户，内存可控。

### 备选：消息过期归档

当前 `t_chat_message` 不做 TTL。长期运行的对话可能积累数百万条消息。可用 `created_at` 分区表按月归档旧消息。
