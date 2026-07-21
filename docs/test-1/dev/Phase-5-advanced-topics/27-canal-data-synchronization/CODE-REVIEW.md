# 27-Canal 数据同步 Code Review

## 评分：9/10（对标 P8，修复后）

| 维度 | 修复前 | 修复后 | 说明 |
|------|--------|--------|------|
| 架构设计 | 9 | 9 | Canal + RocketMQ + ES 标准增量同步架构 |
| 分布式安全 | 7.5 | 9 | 防乱序（ExternalGte）、标记删除、幂等消费 |
| 代码质量 | 8 | 9 | 消费者兼容双格式、异常分类处理、无冗余字段 |
| 生产可用性 | 7.5 | 9 | 修复版本冲突、删除后乱序、字段映射问题 |
| 面试价值 | 9 | 9.5 | Canal Binlog 同步是大厂高频面试题 |

---

## 发现的问题及修复记录

### P0：DELETE 后乱序 INSERT 导致数据不一致

**问题**：DELETE 操作直接物理删除 ES 文档。如果消息乱序（先到 DELETE，后到旧的 INSERT），
由于文档已不存在，ES 的 external version 机制无法拒绝旧版本写入，导致已删除的数据被重新索引。

**修复**：DELETE 改为**标记删除**（将 status 设为 -1），而非物理删除。
这样 ES 文档始终存在，external version 机制可以正常工作。
搜索时过滤 `status=-1` 的文档。真正的物理删除由全量重建任务执行。

**修复文件**：`NoteIndexSyncConsumer.java`、`ProductIndexSyncConsumer.java`

---

### P1：同一毫秒内多次变更导致 version conflict

**问题**：使用 Canal 的 `ts`（毫秒时间戳）作为 ES external version。
同一毫秒内多次 UPDATE 会产生相同的 version，ES 会拒绝第二次写入（`version_conflict_engine_exception`）。

**修复**：
1. 优先使用 Canal 的 `es`（event sequence）字段作为版本号——这是 Canal 内部的严格递增序列号，不会重复
2. `es` 不可用时降级使用 `ts`（兼容旧版本 Canal）
3. 版本类型从 `External` 改为 `ExternalGte`——允许相同版本号写入（同一毫秒内的多次变更都能成功）

**修复文件**：`NoteIndexSyncConsumer.java`、`ProductIndexSyncConsumer.java`

---

### P1：索引了 t_note 表中不存在的字段

**问题**：`indexNoteFromCanal` 中写入了 `like_count`、`collect_count`、`comment_count` 字段，
但 t_note 表中没有这些字段。Canal 消息中也不会包含，永远返回默认值 0。

**修复**：移除这些不存在的字段。计数数据由计数器服务维护，全量重建时从计数器服务获取。
Canal 增量同步只负责同步 t_note 表中实际存在的字段。

**修复文件**：`NoteIndexSyncConsumer.java`

---

### P3：IndexRebuildJob 中有未使用的 import

**问题**：`PostMapping`、`RestController`、`ArrayList` 未被使用。

**修复**：移除未使用的 import。

**修复文件**：`IndexRebuildJob.java`

---

### 1. Canal Server 部署

| 组件 | 配置 |
|------|------|
| 镜像 | canal/canal-server:v1.1.7 |
| 网络 | host 模式 |
| 管理端口 | 11111 |
| 消息模式 | RocketMQ（flatMessage=true） |
| 实例数 | 2（note_instance + product_instance） |

### 2. 实例配置

| 实例 | 监听库表 | MQ Topic | slaveId |
|------|----------|----------|---------|
| note_instance | my_xhs_content.t_note | NOTE_INDEX_TOPIC | 1001 |
| product_instance | my_xhs_product.t_spu, t_sku | PRODUCT_INDEX_TOPIC | 1002 |

### 3. 消费者适配

消费者兼容两种消息格式：
- **Canal 原始格式**：`{database, table, type, data[], old[], ts}`
- **应用层扁平格式**：`{type, noteId, title, content, ...}`（兼容旧消息/手动触发）

---

## 技术亮点

| # | 亮点 | 面试价值 |
|---|------|----------|
| 1 | **Canal Binlog 监听** — 零侵入，不改业务代码 | ⭐⭐⭐⭐⭐ |
| 2 | **External Version 防乱序** — Binlog 时间戳作为 ES 版本号 | ⭐⭐⭐⭐⭐ |
| 3 | **双格式兼容** — 消费者同时支持 Canal 原始格式和扁平格式 | ⭐⭐⭐⭐ |
| 4 | **异常分类处理** — 可重试异常抛出触发 MQ 重试，不可重试异常跳过 | ⭐⭐⭐⭐ |
| 5 | **全量重建兜底** — 断点续传 + 分布式锁 + BulkRequest 批量写入 | ⭐⭐⭐⭐⭐ |
| 6 | **Canal 专用 MySQL 用户** — 最小权限原则（REPLICATION SLAVE/CLIENT） | ⭐⭐⭐ |
| 7 | **多实例隔离** — 笔记和商品独立 Canal 实例，互不影响 | ⭐⭐⭐⭐ |

---

## 面试话术

### Q1: 你们的 MySQL 和 ES 怎么保持数据同步的？

A: 我们采用 Canal + RocketMQ 的增量同步方案：
1. Canal 伪装为 MySQL Slave，监听 Binlog（ROW 格式）
2. 解析到 INSERT/UPDATE/DELETE 事件后，发送到 RocketMQ
3. 搜索服务消费 MQ 消息，构建 ES 文档并写入
4. 使用 Binlog 时间戳作为 ES external version，防止消息乱序导致旧数据覆盖新数据
5. 全量重建作为兜底：每天凌晨 4 点定时执行，支持断点续传

### Q2: Canal 同步延迟怎么处理？

A: 正常延迟 < 5 秒，可接受。异常情况：
1. 监控 MQ 消费积压（消费者 lag）→ 扩容消费者实例
2. Canal 位点异常 → 重置位点从最新位置开始
3. 极端情况（Canal 宕机超过 Binlog 保留期）→ 手动触发全量重建

### Q3: 如何保证消息不丢？

A: 三层保障：
1. Canal 位点持久化（文件/ZK），重启后从断点继续
2. RocketMQ 消费失败自动重试（maxReconsumeTimes=3）
3. 全量重建定时任务兜底（修复 Canal 漏同步的数据）

### Q4: 如何防止消息乱序？

A: 使用 ES external version 机制：
- 每条消息携带 Binlog 时间戳（ts 字段）
- 写入 ES 时指定 versionType=External，version=ts
- 如果 ES 中已有更新的版本，旧消息写入会被拒绝（version conflict）
- 保证最终一致性：即使消息乱序到达，ES 中始终是最新数据

---

## 验证结果

| 测试项 | 结果 |
|--------|------|
| Canal Server 启动 | ✅ |
| note_instance 连接 MySQL 成功 | ✅ |
| product_instance 连接 MySQL 成功 | ✅ |
| Binlog 位点定位成功 | ✅ |
| RocketMQ Producer 启动 | ✅ |
| INSERT → NOTE_INDEX_TOPIC 消息 | ✅ |
| UPDATE → NOTE_INDEX_TOPIC 消息 | ✅ |
| DELETE → NOTE_INDEX_TOPIC 消息 | ✅ |
| 消息格式正确（flatMessage=true） | ✅ |
| 搜索服务编译通过 | ✅ |

---

## 文件清单

| 文件 | 说明 |
|------|------|
| config/canal/conf/canal.properties | Canal Server 全局配置 |
| config/canal/conf/note_instance/instance.properties | 笔记实例配置 |
| config/canal/conf/product_instance/instance.properties | 商品实例配置 |
| docker-compose.yml | 新增 Canal 服务 |
| my-xhs-search/.../NoteIndexSyncConsumer.java | 重写：兼容 Canal 原始格式 |
| my-xhs-search/.../ProductIndexSyncConsumer.java | 重写：兼容 Canal 原始格式 |
| my-xhs-search/.../IndexRebuildJob.java | 修正字段映射 |
