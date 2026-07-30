# Search 索引同步 Canal → MQ → ES — 深度技术分析

> 关联源码：`NoteIndexSyncConsumer.java` / `ProductIndexSyncConsumer.java` / `IncrementalIndexSyncJob.java` / `IndexRebuildJob.java` / `IndexInitializer.java`

---

## 业务背景

MySQL 中的数据变更需要实时同步到 ES，保证搜索结果的时效性。

```
笔记发布/更新/删除 → MySQL t_note
    ↓（< 1 秒）
ES note_index 同步更新
    ↓
用户搜索 → ES 返回最新结果
```

同步方案对比：

| 方案 | 实时性 | 复杂度 | 数据一致性 | 业务侵入 |
|---|---|---|---|---|
| 双写（业务代码同步写 ES） | 高 | 低 | 弱（ES 写失败不处理） | 高（改业务代码） |
| **Canal → MQ → ES** | 高 | 中 | 强（MQ 重试 + 补偿） | 无 |
| 定时全量重建 | 低（小时级） | 低 | 强 | 无 |

---

## 架构

```
MySQL Binlog
    ↓
Canal Server（伪装为 MySQL Slave 监听 Binlog）
    ↓
RocketMQ（NOTE_INDEX_TOPIC / PRODUCT_INDEX_TOPIC）
    ↓
Consumer（NoteIndexSyncConsumer / ProductIndexSyncConsumer）
    ├─ 解析 Canal 格式（支持 raw + flat 两种）
    ├─ ExternalGte 版本控制防乱序
    ├─ INSERT/UPDATE → ES IndexRequest
    ├─ DELETE → ES UpdateRequest (status=-1) 软删
    └─ 失败 → Redis Set（补偿用）
    ↓
补偿：IncrementalIndexSyncJob（@Scheduled 5min）
    └─ SMEMBERS 失败 Set → MySQL 查最新 → ES Bulk API 重试
```

---

## Canal 格式

Canal 推送的消息有两种格式：

### raw 格式

```json
{
  "database": "my_xhs_content",
  "table": "t_note",
  "type": "INSERT",
  "es": 1722294000123,
  "data": [{"id": 20001, "title": "...", "status": 1, ...}]
}
```

### flat 格式

```json
{
  "noteId": 20001,
  "type": "UPDATE"
}
```

`NoteIndexSyncConsumer` 同时兼容两种格式。通过检测 `database` 字段是否存在来区分。

---

## 防乱序：ExternalGte

### 问题

Canal 推送的 Binlog 事件可能乱序到达：

```
时间线：
  T0: UPDATE t_note SET title='A' → Canal 发送 es=100
  T1: UPDATE t_note SET title='B' → Canal 发送 es=101
  T2: Consumer 收到 es=101 → ES 更新 title='B'
  T3: Consumer 收到 es=100 → ES 更新 title='A' ❌ 旧数据覆盖新数据
```

### 解决方案

```java
IndexRequest<Map> request = new IndexRequest<>()
    .index(noteIndexName)
    .id(String.valueOf(noteId))
    .source(sourceMap)
    .version(opseq)                    // Canal es 事件序号
    .versionType(VersionType.ExternalGte); // 仅当版本号 >= 当前时写入
```

**ExternalGte 语义**：如果提供的 version >= ES 中当前文档的 version，则写入。否则忽略。

```
T2: ES 收到 version=101 → 101 >= 文档版本(0) → 写入 title='B', version=101
T3: ES 收到 version=100 → 100 < 版本(101) → 忽略 ❌ 旧数据被丢弃
```

---

## 软删除

Canal 收到 DELETE 事件时，Consumer **不从 ES 删除文档**，而是更新 `status=-1`：

```java
Map<String, Object> update = Map.of("status", -1);
UpdateRequest<Map, Map> request = new UpdateRequest<>()
    .index(indexName)
    .id(String.valueOf(id))
    .doc(update)
    .version(opseq)
    .versionType(VersionType.ExternalGte);
```

**为什么软删不是硬删**：
- 硬删后文档不存在，version 信息丢失
- 如果 DELETE 事件先于 UPDATE 事件到达：
  ```
  T0: DELETE → 硬删文档（version 丢失）
  T1: UPDATE → ES 没有该文档 → 重新创建 → 旧数据生效 ❌
  ```
- 软删保留文档和 version，UPDATE 事件到达时 version 不够高会被拒绝

---

## 消费失败补偿

### 一级：Consumer 内部重试

```java
@RocketMQMessageListener(maxReconsumeTimes = 3)
```

MQ 自动重试 3 次。超过 3 次进入死信队列。

### 二级：Redis Set 记录失败

Consumer 每次消费失败后，将 noteId/spuId 写入 Redis Set：

```java
stringRedisTemplate.opsForSet().add("myxhs:es:sync:failed:note", String.valueOf(noteId));
stringRedisTemplate.expire("myxhs:es:sync:failed:note", 1, TimeUnit.HOURS);
```

### 三级：定时补偿

```java
@Scheduled(cron = "0 */5 * * * ?") // 每 5 分钟
public void compensate() {
    // 1. SMEMBERS 失败 Set
    // 2. 从 MySQL 查询最新数据
    // 3. ES Bulk API 批量写入
    // 4. 成功 → SREM 移除
}
```

---

## 全量重建

### 触发方式

1. **定时**：每天凌晨 4 点（`@Scheduled(cron = "0 0 4 * * ?")`）
2. **手动**：`POST /api/search/index/rebuild`

### 流程

```java
// IndexRebuildJob
// 分页扫描 MySQL（batch=500，keyset 分页）
// 构建 BulkRequest → Bulk API 写入 ES
// Redis 记录进度（断点续传）
```

**为什么需要全量重建**：
- Canal 只消费增量 Binlog，如果 Canal 挂了一段时间，数据不一致
- ES 索引 mapping 变更后需要重建
- 数据修复场景

---

## 面试 Q&A

**Q: 为什么用 Canal 不用双写？**
A: 双写需要在业务代码的每个写操作后同步写 ES，业务侵入大。Canal 监听 Binlog 对业务代码零侵入。

**Q: ExternalGte 解决了什么问题？**
A: 乱序问题。Binlog 事件可能乱序到达 MQ，Consumer 消费顺序不确定。ExternalGte 保证只有更新的版本才能覆盖。

**Q: 软删为什么用 status=-1 而不是 DELETE ？**
A: 如果直接 DELETE 文档，version 信息丢失。假如先 DELETE 再 UPDATE（乱序），UPDATE 时 ES 没有该文档，会重新创建，导致已删除的数据重新出现。

**Q: 全量重建时，增量数据会不会丢失？**
A: 全量重建期间 Canal 仍然在增量同步。全量重建写入的是重建开始时刻的快照，增量同步写入的是重建期间的变更。两者可能存在冲突，但 ExternalGte 版本控制会保证最终一致性——增量同步的 version 更高。

---

## 生产实验

已验证：
- ES note_index 存在 7 条文档 ✅
- ES product_index 存在 1 条文档 ✅
- 增量补偿 Job 代码逻辑已审查 ✅
- 全量重建 Job 代码逻辑已审查 ✅

未验证（需 Canal + RocketMQ 配合）：
- Canal 消息消费 → 索引同步
- ExternalGte 防乱序效果
- 消费失败 → 补偿 Job 恢复

---

## 发散

### CDC 工具替代方案

| 工具 | 优点 | 缺点 |
|---|---|---|
| Canal | MySQL 原生 Binlog 支持 | 需要额外部署 |
| Debezium | Kafka Connect 生态，支持多数据库 | 引入 Kafka，架构重 |
| DTS（云服务） | 免运维 | 供应商锁定 |

### 索引版本号策略

当前使用 Canal 的 es 字段作为 version。es 是 Canal 生成的事件序号（毫秒级时间戳 + 自增序列）。如果同一毫秒内有两个事件，es 可能相同，ExternalGte（>=）可以处理这种场景（允许同版本覆盖）。

如果用自增序列号替代 es，可以保证严格递增但需要 Canal 配合配置。
