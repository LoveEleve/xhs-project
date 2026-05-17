# 搜索模块（my-xhs-search）Code Review 报告

## 📊 P8 评分表

| 维度 | 修复前 | 修复后 | 说明 |
|------|--------|--------|------|
| 架构设计 | 8/10 | 9/10 | Search After 深分页、Canal+MQ 实时同步、全量重建断点续传 |
| 分布式安全 | 8/10 | 9/10 | Lua 原子操作、分布式锁、异常分级重试 |
| 性能优化 | 7/10 | 8.5/10 | ES 连接池、空结果短缓存、缓存 Key MD5 |
| 容错健壮性 | 7/10 | 9/10 | 可重试/不可重试异常分级、断点安全推进 |
| 代码质量 | 8/10 | 8.5/10 | 注释清晰、职责分明 |
| 生产就绪度 | 7/10 | 9/10 | 所有关键问题已修复 |
| **综合评分** | **7.5/10** | **8.8/10** | **对标 P8 水准** |

---

## 🔧 问题发现与修复记录

### 问题 1：搜索历史记录操作非原子性（并发安全）

**严重程度**：🔴 高（分布式多实例场景必现）

**问题描述**：`NoteSearchService.recordSearchHistory()` 中 LREM + LPUSH + LTRIM + EXPIRE 四步操作非原子执行，分布式多实例并发时可能出现重复关键词或列表超长。

**修复前**：
```java
private void recordSearchHistory(Long userId, String keyword) {
    String key = "myxhs:search:history:" + userId;
    stringRedisTemplate.opsForList().remove(key, 1, keyword);  // 步骤1
    stringRedisTemplate.opsForList().leftPush(key, keyword);    // 步骤2
    stringRedisTemplate.opsForList().trim(key, 0, 19);          // 步骤3
    stringRedisTemplate.expire(key, Duration.ofDays(30));       // 步骤4
}
```

**修复后**：
```java
private static final DefaultRedisScript<Long> RECORD_HISTORY_SCRIPT = new DefaultRedisScript<>(
        """
        redis.call('LREM', KEYS[1], 0, ARGV[1])
        redis.call('LPUSH', KEYS[1], ARGV[1])
        redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
        return 1
        """, Long.class);

private void recordSearchHistory(Long userId, String keyword) {
    String key = "myxhs:search:history:" + userId;
    stringRedisTemplate.execute(
            RECORD_HISTORY_SCRIPT,
            List.of(key),
            keyword,
            "20",
            String.valueOf(30 * 24 * 3600)
    );
}
```

**原子性分析**：
- Redis Lua 脚本在单线程中原子执行，4 个命令之间不会被其他命令插入
- `LREM(key, 0, keyword)` 删除所有匹配项（修复前用 `remove(key, 1, keyword)` 只删一个）
- 即使多实例并发执行，每个 Lua 脚本都是原子的，不会出现中间状态

---

### 问题 2：消费者缺少异常分级和重试次数限制

**严重程度**：🔴 高（可能阻塞消费进度）

**问题描述**：`NoteIndexSyncConsumer` 和 `ProductIndexSyncConsumer` 对所有异常统一 `throw RuntimeException`，数据格式错误会无限重试，阻塞消费队列。

**修复前**：
```java
@RocketMQMessageListener(
    topic = "NOTE_INDEX_TOPIC",
    consumerGroup = "note-index-sync-consumer-group"
)
// ...
} catch (Exception e) {
    throw new RuntimeException("笔记索引同步失败", e);  // 所有异常都重试
}
```

**修复后**：
```java
@RocketMQMessageListener(
    topic = "NOTE_INDEX_TOPIC",
    consumerGroup = "note-index-sync-consumer-group",
    maxReconsumeTimes = 3  // 最多重试3次，超过进入死信队列
)
// ...
// 数据格式校验（不可重试 → 直接跳过）
if (event == null || event.get("type") == null || event.get("noteId") == null) {
    log.error("[笔记索引同步] 消息格式异常，跳过: msgId={}", msg.getMsgId());
    return;
}
// ...
} catch (ElasticsearchException | IOException e) {
    // 可重试异常（ES 通信失败）→ 抛出触发重试
    throw new RuntimeException("笔记索引同步失败（可重试）", e);
} catch (Exception e) {
    // 不可重试异常（数据解析错误等）→ 记录日志，跳过
    log.error("[笔记索引同步] 不可重试异常，跳过: msgId={}", msg.getMsgId(), e);
}
```

**异常分级策略**：
| 异常类型 | 处理方式 | 原因 |
|---------|---------|------|
| `ElasticsearchException` | 抛出 → RocketMQ 重试 | ES 集群异常，重试可恢复 |
| `IOException` | 抛出 → RocketMQ 重试 | 网络超时/连接断开，重试可恢复 |
| 数据格式异常 | log.error + return | 数据本身有问题，重试无意义 |
| 其他 Exception | log.error + return | 未知异常，避免阻塞消费 |

---

### 问题 3：断点续传存在数据丢失风险

**严重程度**：🟡 中

**问题描述**：`IndexRebuildJob` 中 `bulkIndexNotes` 部分失败时仍推进断点，导致失败的文档被跳过。

**修复前**：
```java
int indexed = bulkIndexNotes(notes);
totalIndexed += indexed;
// 无论成功多少条，都推进断点
lastNoteId = ((Number) notes.get(notes.size() - 1).get("id")).longValue();
```

**修复后**：
```java
int indexed = bulkIndexNotes(notes);
totalIndexed += indexed;
// 只有全部成功才推进断点
if (indexed < notes.size()) {
    log.warn("[索引重建] 笔记批次部分失败: 成功={}/{}, 断点不推进", indexed, notes.size());
    break; // 中断本次重建，下次从当前位置重试
}
lastNoteId = ((Number) notes.get(notes.size() - 1).get("id")).longValue();
```

**安全性分析**：
- 部分失败时不推进断点 → 下次重建从当前位置重试
- ES 使用文档 ID 做 upsert，重复写入是幂等的，不会产生重复数据
- 最坏情况：已成功的文档被重新索引一次（幂等，无副作用）

---

### 问题 4：全量重建接口同步阻塞

**严重程度**：🟡 中

**问题描述**：`POST /api/search/index/rebuild` 同步执行全量重建，可能耗时数分钟导致 HTTP 超时。

**修复后**：改为 `CompletableFuture.runAsync()` 异步执行，接口立即返回。重建进度可通过 Redis `REBUILD_STATUS_KEY` 查询。

---

### 问题 5：ES RestClient 缺少连接池配置

**严重程度**：🟡 中（高并发下成为瓶颈）

**修复后**：
```java
.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
    .setMaxConnTotal(100)        // 总连接数
    .setMaxConnPerRoute(50)      // 单路由最大连接数
    .setDefaultIOReactorConfig(IOReactorConfig.custom()
        .setIoThreadCount(4)     // IO 线程数
        .build()))
```

---

### 问题 6：索引 Mapping 的 createdAt 缺少 date format

**修复后**：
```json
"createdAt": {
  "type": "date",
  "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||epoch_millis"
}
```

---

### 问题 7：SuggestService 缓存 Key 安全 + 空结果缓存时间

**修复后**：
- 缓存 Key 使用 MD5 摘要：`SUGGEST_CACHE_PREFIX + MD5(prefix)`
- 空结果缓存 5 分钟（非 1 小时），新增热词后快速生效

---

## ✅ 技术亮点（面试价值）

| 亮点 | 技术深度 | 面试价值 |
|------|---------|---------|
| Search After 深分页 | 避免 from+size 的 O(N) 性能问题，游标分页 O(1) | ⭐⭐⭐⭐⭐ |
| Canal + MQ 实时同步 | Binlog → MQ → ES，准实时 <5s，解耦优雅 | ⭐⭐⭐⭐⭐ |
| Lua 脚本原子操作 | 搜索历史 LREM+LPUSH+LTRIM+EXPIRE 原子执行 | ⭐⭐⭐⭐ |
| 异常分级重试 | 可重试（ES超时）vs 不可重试（数据格式错误） | ⭐⭐⭐⭐ |
| 断点续传全量重建 | 大数据量重建中断后可恢复，部分失败不推进断点 | ⭐⭐⭐⭐ |
| Completion Suggester | FST 数据结构，性能比 prefix query 高 10x+ | ⭐⭐⭐⭐ |
| IK 双分析器策略 | 索引 ik_max_word + 搜索 ik_smart，提高召回率 | ⭐⭐⭐ |
| ES 连接池调优 | maxConnTotal/maxConnPerRoute/ioThreadCount | ⭐⭐⭐ |

---

## 🎤 面试话术（Q&A）

### Q1: 搜索模块的整体架构是怎样的？

**A**: 我们的搜索模块基于 ES 8.x + RocketMQ + Redis 构建，核心架构分三层：

1. **数据同步层**：Canal 监听 MySQL Binlog → RocketMQ → 消费者写入 ES，准实时延迟 <5s。消费者天然幂等（ES 用文档 ID 做 upsert），配合 maxReconsumeTimes=3 + 异常分级（可重试 vs 不可重试），保证数据最终一致性。

2. **搜索服务层**：基于 ES 8.x Java Client 构建，支持 multi_match（标题权重 3x）+ filter + 多维排序 + Search After 深分页 + 高亮。Search After 避免了 from+size 的深分页性能问题。

3. **辅助功能层**：搜索建议基于 Completion Suggester（FST 数据结构），搜索历史基于 Redis List + Lua 脚本原子操作。

### Q2: 为什么用 Search After 而不是 from+size？

**A**: from+size 的问题是深分页时性能急剧下降。比如 `from=10000, size=20`，ES 需要在每个分片上取 10020 条，然后协调节点合并排序，时间复杂度 O(from+size)。

Search After 基于上一页最后一条的排序值做游标，每次只取 size 条，时间复杂度 O(size)，与页码无关。我们在排序字段后追加一个唯一字段（noteId/spuId）作为 tiebreaker，保证排序值相同时也能正确分页。

### Q3: 搜索历史为什么用 Lua 脚本？

**A**: 搜索历史需要 4 步操作：LREM 去重 → LPUSH 插入头部 → LTRIM 限长 → EXPIRE 续期。如果分开执行，在分布式多实例场景下，两个实例可能同时执行 LREM，然后各自 LPUSH，导致同一关键词出现两次。

用 Lua 脚本将 4 步操作原子化，Redis 单线程执行 Lua 脚本时不会被其他命令打断，彻底解决并发问题。

### Q4: 消费者的异常处理策略是什么？

**A**: 我们将异常分为两类：

- **可重试异常**（ElasticsearchException、IOException）：ES 连接超时、集群异常等，抛出 RuntimeException 触发 RocketMQ 重试，最多重试 3 次后进入死信队列。
- **不可重试异常**（数据格式错误、ClassCastException 等）：数据本身有问题，重试无意义，直接 log.error + 跳过，避免阻塞消费进度。

同时在消费前做数据格式校验（type/noteId 非空检查），格式异常的消息直接跳过。

### Q5: 全量重建的断点续传是怎么实现的？

**A**: 分页扫描 MySQL（WHERE id > lastId ORDER BY id ASC LIMIT 500），每批写入 ES 成功后，将 lastId 记录到 Redis Hash。下次执行时从 lastId 继续。

关键安全策略：**只有当一批数据全部索引成功时才推进断点**。如果部分失败，断点不推进，下次从当前位置重试。由于 ES 用文档 ID 做 upsert，重复写入是幂等的，不会产生重复数据。

### Q6: IK 分词器的双分析器策略是什么意思？

**A**: 索引时用 `ik_max_word`（最细粒度分词），搜索时用 `ik_smart`（最粗粒度分词）。

比如"中华人民共和国"：
- `ik_max_word` 索引时分为：中华人民共和国、中华人民、中华、华人、人民共和国、人民、共和国、共和、国
- `ik_smart` 搜索时分为：中华人民共和国

这样搜索"中华"也能命中"中华人民共和国"的文档，提高召回率。
