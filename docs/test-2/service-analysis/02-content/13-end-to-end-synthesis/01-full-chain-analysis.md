# content 模块全链路一致性分析

> 前置阅读：03~12 共 10 份深度文档。本文不是讲解某个单一系统，而是**把 10 个系统串起来**——
> 回答"最坏情况延迟多少"、"中间件宕机时哪些功能降级"、"两个用户同时编辑会发生什么"。

---

## 1. 全链路端到端时间线

### 1.1 正常路径（所有组件可用）

```
publishNote 发起
  │
  ├─ T=0ms    DFA 检测（纯内存，Trie 树 O(n)，~0.1ms）
  ├─ T=0.1ms  buildNote + 显式 nextId()（纯内存，~0.1ms）
  ├─ T=1ms    noteMapper.insert(t_note)          ← DB Write（~5ms）
  ├─ T=6ms    localMessageMapper.insert(localMsg)  ← DB Write（~5ms）
  ├─ T=11ms   registerSynchronization(...)         ← 注册回调（纯内存）
  ├─ T=11ms   return note.getId()                  ← 🎯 用户收到 200
  │
  ├─ T=12ms   commit()                             ← DB 事务提交
  │
  ├─ T=12ms   afterCommit: 立即 delayDoubleDelete  ← 第一次，同步，~0.5ms
  ├─ T=12.5ms afterCommit: asyncSend FEED_TOPIC    ← 异步，~1ms返回
  │               │
  │               ├─ ~5ms → Broker 收到消息         ← Socket 往返
  │               │
  │               ├─ <1s  → FeedPushConsumer 消费    ← Consumer 轮询延迟
  │               │   ├─ 大V(粉丝≥10w): ZADD 作者发件箱（拉模式，~1ms）
  │               │   └─ 普通: Pipeline ZADD 粉丝收件箱
  │               │       ├─ 500 人/批 × N 批
  │               │       └─ 每批更新 Redis cursor
  │               │
  │               ├─ ~2-5s → Canal binlog 捕获       ← Canal 轮询 MySQL binlog
  │               │    → RocketMQ NOTE_INDEX_TOPIC
  │               │    → NoteIndexSyncConsumer
  │               │    → ES Bulk Index               ← ES refresh 默认1s
  │               │
  │               └─ ~3-6s → 搜索可见                ← 最慢的环节（Canal 延迟 + ES refresh）
  │
  └─ T=12.5s  afterCommit 返回，Tomcat 线程释放
```

**用户感知延迟**：11ms（从请求到响应）。**搜索可见延迟**：3-6 秒（Canal binlog 采集 + ES index refresh）。

### 1.2 各环节延迟成分分析

| 环节 | 单次延迟 | 批量效应 | 最坏情况 |
|------|:------:|------|:------:|
| DFA | ~0.1ms | 与文本长度成正�� | 20000 字正文: ~2ms |
| MySQL INSERT | ~5ms | 无（单条 INSERT） | 同：~5ms |
| MQ asyncSend | ~1ms（方法返回） | 无（异步） | Broker 不可达→callback 3s超时 |
| Feed 推送 | 500粉丝/批×Pipeline | 10万粉丝=200批 | Consumer 崩溃→断点续推(120s后) |
| Canal → ES | 1-3s（binlog 捕获延迟） | 无 | Canal 位点前数据不同步 |
| ES index refresh | 1s | 默认1s | 无批次影响 |

---

## 2. 故障模式矩阵

### 2.1 单点故障分析

| 故障 | 影响范围 | publishNote | getNoteDetail | getUserNotes | 恢复时间 |
|------|:------:|:--:|:--:|:--:|:--:|
| **Redis 不可达** | 缓存 | ✅ 仍可用（降级直接查 DB） | ✅ 降级查 DB | ✅ 无影响（本不缓存） | 恢复即回填 |
| **MySQL Master 宕机** | 写操作 | ❌ 全部写操作失败 | ✅ 从库可读 | ✅ 从库可读 | JDBC Multi-Host 自动切换（~10s） |
| **MySQL Slave 宕机** | 读操作 | ✅ 主库可写 | ✅ 主库兜底读 | ✅ 主库兜底读 | JDBC Multi-Host 自动切换（~10s） |
| **RocketMQ Broker 宕机** | 异步消息 | ⚠️ callback 3s超时→onException→retryFailedMessages 补偿 | ✅ 无影响 | ✅ 无影响 | 最多60s（定时扫表周期） |
| **Canal 宕机** | ES 索引 | ✅ 笔记正常入库 | ✅ 详情正常返回 | ✅ 列表正常返回 | 恢复后自动消费断点 |
| **ES 宕机** | 搜索 | ✅ 笔记正常入库（与 ES 无关） | ✅ 详情正常返回 | ✅ 列表正常返回 | ES 恢复后 Canal 补发未同步数据 |
| **Nacos 宕机** | 服务发现 | ✅ 本地快照兜底 | ✅ 本地快照兜底 | ✅ 本地快照兜底 | 恢复后重新注册 |

### 2.2 多重故障推演

**场景 A：Redis + MQ 同时不可达**

```
publishNote:
  ├─ DFA ✅（纯内存）
  ├─ INSERT t_note ✅（MySQL 正常）
  ├─ INSERT t_local_message ✅（MySQL 正常）
  ├─ afterCommit.delayDoubleDelete ❌（Redis 不可达→L3 MQ 兜底→MQ 也不可达→失败）
  └─ afterCommit.asyncSend ❌（MQ 不可达→onException→retryFailedMessages 等待）

结果：
  - 笔记入库 ✅
  - 缓存一致：详情缓存旧值保留，直到 TTL 过期（30min）
  - Feed 推送：延迟 60s（等 retryFailedMessages）→ MQ 仍不可达→继续重试→3次后死信
  - ES 索引：依赖 Canal（MySQL binlog 正常→Canal→MQ→ES 链路仍工作，Canal 和 ES 独立于 Redis）
```

**场景 B：MySQL Master 宕机 + publishNote 正在执行**

```
publishNote:
  ├─ DFA ✅（纯内存，已完成）
  ├─ buildNote + nextId() ✅（纯内存，已完成）
  └─ noteMapper.insert(t_note) ❌（Master 不可达）
       → @Transactional 回滚
       → localMessageMapper.insert 未执行
       → BizException 返回给用户
```

**场景 C：Canal 延迟 + 用户查询搜索**

```
T=0s: publishNote → INSERT t_note
T=1s: 用户搜索 → ES query → 旧索引，查不到新笔记
T=3s: Canal binlog 捕获 → MQ NOTE_INDEX_TOPIC
T=4s: NoteIndexSyncConsumer → ES Bulk Index
T=5s: ES refresh → 新笔记可搜索
```

Canal 的 binlog 捕获有 1-3s 延迟，ES 默认 1s refresh。所以搜索可见延迟 = 2-5s。如果笔记需要实时可搜索（如热门话题），应改为 `publishNote` 的 `afterCommit` 中直接调用 ES Index API，不走 Canal 链路。

---

## 3. 并发编辑：两个用户同时修改同一篇笔记

### 3.1 当前行为

```java
// updateNote 的流程
Note note = noteMapper.selectById(noteId);   // ① 读当前值
// 此时另一个用户也在执行 selectById，读到同一个值
note.setTitle(request.getTitle());           // ② 设置新值
noteMapper.updateById(note);                 // ③ 写回 DB
```

**两个用户同时编辑**：

```
用户 A:          用户 B:
T1: selectById    T1: selectById
  → title="原题"    → title="原题"
T2: setTitle("A")  T2: setTitle("B")
T3: updateById     T3: updateById
  → title="A"        → title="B"
```

最终 title = "B"——用户 A 的修改被**静默覆盖**。两个更新都成功返回 200，但对用户 A 来说，他的修改消失了。

### 3.2 MySQL 默认行为

```
UPDATE t_note SET title="A", updated_at=NOW() WHERE id=?
  → MySQL: OK, 1 row affected

UPDATE t_note SET title="B", updated_at=NOW() WHERE id=?
  → MySQL: OK, 1 row affected（覆盖了第一次更新的 title 和 updated_at）
```

两次 `UPDATE` 都成功了——因为 MySQL 的 `UPDATE` 不加行锁（除非在事务中先 `SELECT ... FOR UPDATE`），也没有内置的版本检查。

### 3.3 修复方案：乐观锁 @Version

```java
// BaseEntity 中加字段
@Version
private Integer version;  // 每次 update 自动 +1

// 修改 updateById 生成的 SQL（MyBatis-Plus 自动处理）
UPDATE t_note SET title="A", version=version+1, updated_at=NOW()
WHERE id=? AND version=3   ← 校验版本号

// 并发场景：
用户A: UPDATE ... WHERE version=3 → version 匹配，执行 → version=4
用户B: UPDATE ... WHERE version=3 → version 不匹配！→ 0 rows affected
       → MyBatis-Plus 抛 OptimisticLockException → 返回"数据已被他人修改，请刷新后重试"
```

MyBatis-Plus 的 `@Version` 注解会**自动**拦截 `updateById`——不需要改业务代码。只需要：1）加字段 2）加注解 3）前端处理 409 冲突响应。

### 3.4 为什么当前没加 @Version？

1. **训练营项目**：并发编辑是小概率事件，覆盖的收益＜引入复杂度
2. **编辑场景低频**：笔记编辑远少于查看，实际冲突概率接近零
3. **业务不是金融**：丢一次编辑 vs 丢一笔转账——容忍度不同

如果上线到真实用户环境，应该加 `@Version`。代码改动只需加两行——是在所有实体上最便宜的安全保障之一。

---

## 4. 数据增长与归档

### 4.1 问题

三张表会随时间无限增长：

| 表 | 增长速率 | 问题 |
|------|:------:|------|
| t_note | ~1条/每篇笔记 | deleted=1 的记录永久保留 |
| t_comment | ~N条/每篇笔记 | 同上 |
| t_local_message | ~1条/每篇已发布笔记 | status=1 且 push_status=2 的消息已完成，不需要保留 |

### 4.2 影响

| 问题 | 严重度 | 当前状态 |
|------|:------:|------|
| 逻辑删除堆积 | 🟡 | `deleted=1` 的记录膨胀 t_note，所有 `SELECT` 都要过滤 `deleted=0` |
| local_message 无限增长 | 🟡 | `selectPending` 扫描范围不断扩大，虽然索引 `idx_status_retry` 过滤了 status=0 的行 |
| 无自动清理 | 🔴 | 完全没有任何定时清理逻辑——依赖人工运维 |

### 4.3 归档策略

```
凌晨 4 点定时任务（XXL-Job 或 @Scheduled）:

1. t_local_message 归档
   DELETE FROM t_local_message
   WHERE status=1 AND push_status=2 AND created_at < NOW() - INTERVAL 7 DAY
   -- 已发送 + 已推送 + 7天前 → 没有保留价值

2. t_note 物理删除
   DELETE FROM t_note
   WHERE deleted=1 AND updated_at < NOW() - INTERVAL 30 DAY
   -- 逻辑删除超过30天 → 用户不会恢复了

3. t_comment 物理删除
   DELETE FROM t_comment
   WHERE deleted=1 AND updated_at < NOW() - INTERVAL 30 DAY
```

**注意事项**：

- `DELETE` 不要一次删太多——分批删（`LIMIT 1000`），避免长时间锁表
- 归档前 `SELECT` 备份到历史表（`t_note_archive`）——万一需要数据恢复
- 定时任务用 `@XxlJob` 而非 `@Scheduled`——XXL-Job 管理后台可以手动触发、查看执行日志
- 归档任务应和业务任务错开：凌晨 4 点（不是流量高峰），且不和 `IndexRebuildJob`（也是凌晨 4 点）共享同一个执行器，避免资源竞争

---

## 5. 深度文档索引

| 你想了解什么 | 看这篇 |
|-------------|--------|
| 敏感词怎么检测的？为什么用 Trie 不用正则？ | `03-dfa/` |
| `@Transactional` 怎么管两笔 INSERT？afterCommit 在哪个线程？ | `04-transaction-aftercommit/` |
| 笔记入库了但 MQ 没发出去怎么办？ | `05-local-message-table/` |
| 为什么删缓存要删两次？500ms 延迟怎么来的？ | `06-cache-strategy/` |
| asyncSend 失败了怎么补救？ | `07-mq-reliability/` |
| 笔记 ID 为什么是 19 位？雪花算法 vs 号段模式？ | `08-id-generation/` |
| 草稿和发布有什么区别？状态机怎么流转？ | `09-note-lifecycle/` |
| 编辑笔记为什么只更新非 null 字段？删除是物理删还是逻辑删？ | `10-note-edit-delete/` |
| 图片上传有什么安全问题？将来怎么切 MinIO？ | `11-file-upload-storage/` |
| 评论支持几层嵌套？游标分页比传统分页好在哪？ | `12-comment-system/` |
| 最坏情况延迟多少？Redis 宕机影响什么？ | 本文（你正在读） |

---

## 6. 生产就绪清单

| 检查项 | 当前状态 | 优先级 |
|--------|:------:|:------:|
| DFA 词库扩展至 5000+ | ❌ 只有 5 个测试词 | P1 |
| 编辑加 @Version 乐观锁 | ❌ 并发编辑会丢失更新 | P1 |
| 评论通知加本地消息表 | ❌ asyncSend 失败永久丢失 | P2 |
| local_message 归档任务 | ❌ 无自动清理 | P2 |
| t_note/t_comment 物理删除 | ❌ deleted=1 永久保留 | P2 |
| 审核流程（AUDITING 状态） | ❌ 直接跳过 | P2 |
| DFA buildTrie 加并发锁 | ❌ TOCTOU 风险 | P3 |
| 文件上传二进制签名检测 | ❌ Content-Type 可伪造 | P3 |
| 超时防护（@Transactional timeout） | ❌ 无限制 | P1 |
| 死信告警（status=3 监控） | ❌ 无 Prometheus 指标 | P2 |
| 缓存命中率监控 | ❌ 无 | P3 |
