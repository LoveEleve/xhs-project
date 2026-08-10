# my-xhs-content 内容模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-content/` |
| 端口 | 19002 |
| 服务名 | `my-xhs-content`（Nacos） |
| 数据库 | `my_xhs_content`（MySQL 13307 主 / 13311 从，读写分离） |
| Java 源文件 | 26 个 |
| 启动类 | `ContentApplication.java` |
| 扫描包 | `com.myxhs.content`, `com.myxhs.common` |

**职责边界**：笔记的发布/编辑/删除/详情、评论的发布/删除/列表（支持楼中楼）、图片上传、敏感词过滤（DFA Trie 树）、Feed 推送可靠性保障（本地消息表 + 定时重试）。

**本模块不调用其他微服务**（无 `@FeignClient`），是纯生产者——向 RocketMQ 推送 `FEED_TOPIC` 和 `NOTIFICATION_TOPIC`。

---

## 1. 数据模型

### 1.1 数据库表（my_xhs_content 库）

**`t_note` — 笔记表**

```sql
id            BIGINT PRIMARY KEY        -- 雪花 ID（业务代码显式调用 IdGeneratorUtil.nextId()）
user_id       BIGINT                    -- 作者 ID
title         VARCHAR(128)              -- 标题
content       TEXT                      -- 正文（最大 20000 字）
images        TEXT (JSON Array)         -- 图片 URL 列表
video_url     VARCHAR(512)              -- 视频 URL
cover_url     VARCHAR(512)              -- 封面图
topic_ids     VARCHAR(512) (JSON)       -- 话题 ID 列表
tags          VARCHAR(512) (JSON)       -- 标签列表
status        TINYINT                   -- 0=草稿 1=审核中 2=已发布 3=已下架
audit_status  TINYINT                   -- 0=待审核 1=通过 2=拒绝
reject_reason VARCHAR(256)              -- 审核拒绝原因
note_type     TINYINT                   -- 0=图文 1=视频
deleted       TINYINT DEFAULT 0         -- 逻辑删除（@TableLogic）
created_at    DATETIME
updated_at    DATETIME
```

索引：`idx_user_id`, `idx_status`, `idx_created_at`

images/topicIds/tags 以 JSON 字符串存 DB，Service 层通过 `ObjectMapper` 序列化/反序列化。

**`t_comment` — 评论表**

```sql
id          BIGINT PRIMARY KEY
note_id     BIGINT                    -- 所属笔记
user_id     BIGINT                    -- 评论者
parent_id   BIGINT DEFAULT 0          -- 父评论 ID（0=一级评论）
reply_to_id BIGINT                    -- 被回复的评论 ID（楼中楼）
content     VARCHAR(1024)             -- 评论内容（限 500 字）
like_count  INT DEFAULT 0             -- 点赞数
deleted     TINYINT DEFAULT 0         -- 逻辑删除
created_at  DATETIME
updated_at  DATETIME
```

索引：`idx_note_id`, `idx_user_id`, `idx_parent_id`

**`t_local_message` — 本地消息表（Feed 可靠性保障）**

```sql
id            BIGINT PRIMARY KEY        -- 雪花 ID（MyBatis-Plus 自动生成）
topic         VARCHAR(64)               -- MQ Topic
body          TEXT (JSON)               -- 消息体（NotePublishEvent）
status        TINYINT                   -- 0=待发送 1=已发送 2=失败 3=死信
retry_count   INT DEFAULT 0             -- 重试次数
push_status   TINYINT                   -- 0=未推送 1=推送中 2=已推送 3=推送失败
push_cursor   INT                       -- 已推送到的粉丝序号
push_total    INT                       -- 总粉丝数
created_at    DATETIME
```

### 1.2 枚举类

| 枚举 | 值 | 说明 |
|------|------|------|
| `NoteStatus` | `DRAFT(0)` → `AUDITING(1)` → `PUBLISHED(2)` → `OFFLINE(3)` | 有状态流转校验 `canTransitTo()` |
| `AuditStatus` | `PENDING(0)`, `APPROVED(1)`, `REJECTED(2)` | 审核状态 |
| `NoteType` | `IMAGE_TEXT(0)`, `VIDEO(1)` | 笔记类型 |

`NoteStatus.canTransitTo()` 定义了合法流转：
```
DRAFT    → AUDITING | PUBLISHED
AUDITING → PUBLISHED | OFFLINE
PUBLISHED → OFFLINE
OFFLINE   → 终态，不可流转
```

---

## 2. 接口清单（16 个 REST 端点）

### 2.1 笔记接口（10 个）— `/api/note`

| 方法 | 路径 | 鉴权 | 限流 | 参数 | 返回 |
|------|------|:----:|------|------|------|
| `POST` | `/api/note/publish` | X-User-Id | 60s/5次 | `NotePublishRequest` | `R<{noteId}>` |
| `POST` | `/api/note/draft` | X-User-Id | — | `NotePublishRequest` | `R<{noteId}>` |
| `PUT` | `/api/note/{id}` | X-User-Id | — | `NoteUpdateRequest` | `R<Void>` |
| `DELETE` | `/api/note/{id}` | X-User-Id | — | — | `R<Void>` |
| `GET` | `/api/note/detail/{id}` | 公开 | — | — | `R<NoteDetailVO>` |
| `GET` | `/api/note/user/{userId}`| 公开 | — | pageNum, pageSize | `R<PageResult<NoteItemVO>>` |
| `GET` | `/api/note/my` | X-User-Id | — | status?, pageNum, pageSize | `R<PageResult<NoteItemVO>>` |
| `POST` | `/api/note/{id}/publish` | X-User-Id | — | — | `R<Void>` |
| `POST` | `/api/note/upload/image` | X-User-Id | 60s/20次 | `MultipartFile` | `R<{url}>` |
| `POST` | `/api/note/{id}/share` | X-User-Id | — | — | `R<Void>` |

### 2.2 评论接口（6 个）— `/api/comment`

| 方法 | 路径 | 鉴权 | 限流 | 参数 | 返回 |
|------|------|:----:|------|------|------|
| `POST` | `/api/comment` | X-User-Id | 60s/10次 | `CommentCreateRequest` | `R<{commentId}>` |
| `DELETE` | `/api/comment/{id}` | X-User-Id | — | — | `R<Void>` |
| `GET` | `/api/comment/list/{noteId}` | 公开 | — | lastId?, pageSize | `R<List<CommentVO>>` |
| `GET` | `/api/comment/children/{parentId}` | 公开 | — | lastId?, pageSize | `R<List<CommentVO>>` |
| `GET` | `/api/comment/count/{noteId}` | 公开 | — | — | `R<{count}>` |
| `GET` | `/api/comment/page/{noteId}` | 公开 | — | pageNum, pageSize | `R<PageResult<CommentVO>>` |

评论列表使用**游标分页**（`WHERE id < lastId ORDER BY id DESC`），传统分页 `page/{noteId}` 作为备用。

---

## 3. 内部架构

```
Controller 层
├── NoteController                  ─┬─ NoteService
└── CommentController               ─┘     │
                                           ├── CommentService
Service 层                                  │     ├── DFAFilter
├── NoteService (566行)        ────────────┤     └── FileStorageService (接口)
│   ├── DFAFilter                          │         └── LocalFileStorageService (实现)
│   ├── CacheHelper                        │
│   ├── IdGeneratorUtil                    │
│   ├── LocalMessageMapper                 │→ MySQL (本地消息表)
│   └── RocketMQTemplate                   │→ RocketMQ (FEED_TOPIC)
├── CommentService (405行)      ───────────┤
│   ├── DFAFilter                          │
│   ├── CacheHelper                        │
│   └── RocketMQTemplate                   │→ RocketMQ (NOTIFICATION_TOPIC)
├── FileStorageService (接口)               │
└── LocalFileStorageService (实现)          │→ 本地磁盘 /data/uploads/

Scheduled Jobs
└── FeedMessageRetryJob (133行)
    ├── retryFailedMessages()       — 每 30s 扫描本地消息表，重试 MQ 发送失败
    └── compensateIncompletePush()  — 每 60s 扫描推送未完成，补偿推送

Filter
└── DFAFilter (305行)               — Trie 树敏感词过滤，双重词库（静态文件 + Redis Set）
    └── Redis Pub/Sub Channel: myxhs:sensitive-word:reload

Config
├── FileUploadConfig                — /uploads/** → 本地磁盘映射
└── RedisPubSubConfig               — RedisMessageListenerContainer Bean
```

### 3.1 Redis Key 清单

| Key 模式 | 类型 | TTL | 用途 |
|---------|------|------|------|
| `myxhs:note:detail:{noteId}` | Cache Aside | 30min | 笔记详情缓存 |
| `myxhs:note:list:user:{userId}` | Cache Aside | — | 用户笔记列表缓存 |
| `myxhs:note:count:{noteId}` | Hash (field=share) | 永久 | 分享计数 |
| `myxhs:comment:list:{noteId}` | Cache Aside | — | 评论列表缓存 |
| `myxhs:comment:count:{noteId}` | Cache Aside | 5min | 评论数缓存 |
| `myxhs:sensitive-word:list` | Set | 永久 | 动态敏感词库 |
| `myxhs:sensitive-word:reload` | Pub/Sub Channel | — | 敏感词更新通知频道 |

### 3.2 MQ 消息（本模块只生产，不消费）

| Topic | 触发场景 | 消息体 | 消费者 |
|-------|---------|--------|--------|
| `FEED_TOPIC` | 发布笔记/发布草稿 | `NotePublishEvent` | `FeedPushConsumer`（home 模块） |
| `NOTIFICATION_TOPIC` | 别人评论我的笔记 | `Map {type, senderId, targetUserId, ...}` | notification 模块 |

### 3.3 本地消息表可靠性保障

笔记入库和 MQ 发送不在同一个分布式事务内——可能出现"DB 写入成功但 MQ 发送失败"。本地消息表解决这个问题：

```
@Transactional
publishNote()
  ├─ noteMapper.insert(note)              ← 笔记入库
  ├─ localMessageMapper.insert(message)   ← 本地消息表（同一事务）
  │     status=0（待发送）
  │
  └─ TransactionSynchronization.afterCommit()
       └─ rocketMQTemplate.asyncSend("FEED_TOPIC", event, callback)
            ├─ onSuccess: localMessageMapper.markSent(id) → status=1
            └─ onException: 不处理 ← 由 FeedMessageRetryJob 兜底

FeedMessageRetryJob.retryFailedMessages()  ← @Scheduled(每 30s)
  ├─ 扫描: status=0 AND created_at < now-60s AND retry_count < 3
  ├─ 重新 syncSend
  └─ 仍失败: incrementRetry → 超过 3 次 → status=3（死信）
```

**为什么 `created_at < now-60s`？** 给刚创建的消息 60 秒宽限期，避免 `afterCommit` 回调还在执行、定时任务就进来重复扫描。

---

## 4. 业务代码详解

### 4.1 发布笔记 — `NoteService.publishNote()`

**源码**：`my-xhs-content/src/main/java/com/myxhs/content/service/NoteService.java:80-150`

```
publishNote(userId, NotePublishRequest)   ← @Transactional
│
├─ 1. DFA 敏感词检测
│      dfaFilter.containsSensitiveWord(title + " " + content)
│      → true → throw BizException("内容包含敏感词")
│
├─ 2. 构建 Note 实体
│      note.id = idGeneratorUtil.nextId()        ← 雪花 ID，业务代码显式赋值
│      note.status = NoteStatus.PUBLISHED(2)
│      note.auditStatus = AuditStatus.APPROVED(1)  ← 当前跳过审核直接发布
│      images/topicIds/tags → ObjectMapper.writeValueAsString → JSON 字符串
│
├─ 3. noteMapper.insert(note)                      ← 入库（同一事务）
│
├─ 4. businessMetrics.recordFeedPush("publish")    ← 业务监控指标
│
├─ 5. 写入本地消息表
│      LocalMessage { topic="FEED_TOPIC", body=NotePublishEvent JSON, status=0 }
│      localMessageMapper.insert(message)           ← 同一事务内
│
├─ 6. 事务提交后回调
│      TransactionSynchronizationManager.registerSynchronization(
│        new TransactionSynchronization() {
│          afterCommit() {
│            ├─ cacheHelper.delayDoubleDelete(NOTE_LIST_USER)  ← 清除列表缓存
│            └─ rocketMQTemplate.asyncSend("FEED_TOPIC", event, new SendCallback() {
│                 onSuccess: localMessageMapper.markSent(msgId)   ← status=1
│                 onException: 不处理 ← FeedMessageRetryJob 补偿
│               })
│          }
│        }
│      )
│
└─ 7. 返回 { noteId: xxx }
```

**关键设计点**：

1. **ID 显式赋值**：`note.setId(idGeneratorUtil.nextId())`，不依赖 MyBatis-Plus 的 `@TableId`。因为 `afterCommit` 回调中需要用到 `note.getId()` 构建 MQ 消息，如果等 insert 后才拿 ID，需要额外一次 select。

2. **本地消息表与笔记在同一事务**：`noteMapper.insert()` 和 `localMessageMapper.insert()` 在同一个 `@Transactional` 内，要么都成功，要么都回滚。不会出现"笔记入库了但消息没记录"。

3. **MQ 在事务提交后才发送**：`afterCommit` 的关键在于——事务还没提交时，MQ 消费者可能已经收到消息并回查 DB，结果查不到笔记。所以必须等 DB 事务提交后再发。

4. **敏感词检测放在最前面**：如果在入库后才检测，用户写了敏感词内容 → DB 已写入 → 抛异常 → 事务回滚 → 但可能已经产生脏数据。

5. **当前 status=2 直接发布，auditStatus=1 直接通过**：说明审核功能尚未实现（代码中没有审核流程），直接跳过审核状态。

---

### 4.2 保存草稿 — `NoteService.saveDraft()`

```java
// 与 publishNote 的区别：
note.setStatus(NoteStatus.DRAFT);       // status=0，不是 PUBLISHED(2)
note.setAuditStatus(AuditStatus.PENDING);  // auditStatus=0

// 不触发 DFA 检测（草稿允许写任何内容）
// 不写本地消息表（草稿不需要推送 Feed）
// 不发 MQ
```

---

### 4.3 草稿发布 — `NoteService.publishDraft()`

```
publishDraft(userId, noteId)   ← @Transactional
│
├─ 1. noteMapper.selectById(noteId)
│      → null → throw NOT_FOUND
│      → note.userId != userId → throw FORBIDDEN
│
├─ 2. DFA 敏感词检测（草稿内容可能包含敏感词）
│
├─ 3. 状态流转校验
│      NoteStatus.DRAFT.canTransitTo(NoteStatus.PUBLISHED) → true
│
├─ 4. 更新状态 + 写入本地消息表
│      updateWrapper.set(status, PUBLISHED).set(auditStatus, APPROVED)
│      localMessageMapper.insert(message)
│
├─ 5. afterCommit: 清除缓存 + 异步发送 FEED_TOPIC
│
└─ 6. 返回
```

**与直接发布的关键差异**：直接发布是新创建 `Note`，草稿发布是更新已有的 DRAFT 记录——需要校验归属（`userId`）、状态流转合法性（`canTransitTo`）、以及重新做 DFA 检测（草稿保存时没做 DFA）。

---

### 4.4 发表评论 — `CommentService.createComment()`

**源码**：`my-xhs-content/src/main/java/com/myxhs/content/service/CommentService.java:74-183`

```
createComment(userId, CommentCreateRequest)   ← @Transactional
│
├─ 1. noteMapper.selectById(noteId)
│      → null → throw "笔记不存在"
│      → status != PUBLISHED → throw "笔记状态异常"
│
├─ 2. DFA 敏感词检测 content
│
├─ 3. 父评论校验（如果 parentId > 0）
│      ├─ commentMapper.selectById(parentId) → 不存在 → throw
│      ├─ parentComment.noteId != noteId → throw "评论不属于该笔记"
│      └─ 如果 parentComment.parentId > 0（父评论本身是子评论）
│           → 修正 parentId = parentComment.parentId ← 只支持两级嵌套
│
├─ 4. replyToId 校验（如果 replyToId > 0）
│      ├─ commentMapper.selectById(replyToId) → 不存在 → throw
│      └─ replyComment.noteId != noteId → throw
│
├─ 5. commentMapper.insert(comment)           ← 入库
│
├─ 6. afterCommit
│      ├─ cacheHelper.delayDoubleDelete(COMMENT_LIST + noteId)
│      ├─ cacheHelper.delayDoubleDelete(COMMENT_COUNT + noteId)
│      └─ if (userId != noteAuthorId)
│           rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC", { type=2(评论), ... })
│              ← 排除自己评论自己的场景
│
└─ 7. 返回 { commentId: xxx }
```

**楼中楼设计**：

```
一级评论 (parent_id=0)
  ├─ 二级 A (parent_id=一级ID, reply_to_id=NULL)     ← 回复一级评论
  ├─ 二级 B (parent_id=一级ID, reply_to_id=A.ID)      ← 回复 A
  └─ 二级 C (parent_id=一级ID, reply_to_id=一级ID)    ← 也回复一级评论

代码自动修正：
  如果前端传了 parent_id=二级A.ID → 自动修正为 parent_id=一级ID
  确保所有二级评论的 parent_id 始终指向一级评论
```

**游标分页**：

```sql
-- 一级评论：倒序（最新在前）
SELECT * FROM t_comment
WHERE note_id = ? AND parent_id = 0 AND id < #{lastId}
ORDER BY id DESC LIMIT #{pageSize}

-- 子评论：正序（按时间线）
SELECT * FROM t_comment
WHERE parent_id = ? AND id > #{lastId}
ORDER BY id ASC LIMIT #{pageSize}
```

一级评论用倒序（`id < lastId`，最新的评论 ID 最大），二级评论用正序（`id > lastId`，按发言时间顺序展示）。

---

### 4.5 DFA 敏感词过滤 — `DFAFilter`

**源码**：`my-xhs-content/src/main/java/com/myxhs/content/filter/DFAFilter.java:1-305`

**核心数据结构**：Trie 树（前缀树）

```
示例词库: ["赌博", "赌场", "诈骗"]

Trie 树结构:
  root
  ├─ 赌 → node
  │    ├─ 博 → end（命中"赌博"）
  │    └─ 场 → end（命中"赌场"）
  └─ 诈 → node
       └─ 骗 → end（命中"诈骗"）

匹配过程（文本 = "我去赌场了"）:
  i=0: '我' → 不在 root → 跳过
  i=1: '去' → 不在 root → 跳过
  i=2: '赌' → 在 root → 匹配"赌场"两个字符 → 命中！
```

时间复杂度 O(n)，n = 文本长度。相比正则表达式逐个匹配 O(n*m)，Trie 树对长文本和大词库有数量级优势。

**双重词库**：

```
初始化（@PostConstruct）
  ├─ 静态词库：classpath:sensitive-words.txt → 加载到内存
  ├─ 动态词库：Redis SMEMBERS myxhs:sensitive-word:list → 合并
  └─ 构建 Trie 树 → volatile trieRoot

动态更新（运营后台操作）
  ├─ Redis SADD / SREM myxhs:sensitive-word:list → 修改词库
  └─ Redis PUBLISH myxhs:sensitive-word:reload "reload" → 通知所有实例
       └─ 每个实例收到消息 → reload() → 重新加载 → 重建 Trie 树
```

`volatile trieRoot` 保证多线程可见性——重建时写新 Map 引用，读取线程立即看到更新。

---

### 4.6 删除评论 — `CommentService.deleteComment()`

```
deleteComment(userId, commentId)   ← @Transactional
│
├─ 1. commentMapper.selectById(commentId)
│      → null → throw NOT_FOUND
│      → comment.userId != userId → throw FORBIDDEN
│
├─ 2. commentMapper.deleteById(commentId)    ← 逻辑删除
│
├─ 3. 如果是一级评论 (parent_id=0) → 级联删除子评论
│      LambdaQueryWrapper.eq(parentId, commentId)
│      commentMapper.delete(wrapper)          ← 批量逻辑删除
│
└─ 4. afterCommit: 清除缓存
```

**为什么一级评论要级联删除？** 一级评论删除后，其下所有子评论失去上下文（"回复 #3 楼" 但 #3 不存在）。级联删除保证评论树的完整性。

---

## 5. Gateway 交互

### 5.1 白名单情况

| 接口 | auth 白名单 | hmac 白名单 |
|------|:----------:|:----------:|
| `/api/note/detail/**` | 是 | 是 |
| `/api/note/user/**` | 是 | 是 |
| `/api/comment/list/**` | 是 | 是 |
| `/api/comment/children/**` | 是 | 是 |
| `/api/comment/count/**` | 是 | 是 |
| `/api/comment/page/**` | 是 | 是 |
| 其他 `/api/note/**`, `/api/comment/**` | 否 | 否 |

公开接口（笔记详情、用户笔记列表、评论列表/计数）JWT + HMAC 双白名单放行。写操作（发布/编辑/删除）需要认证 + HMAC 签名。

### 5.2 Gateway 路由规则

```yaml
- id: content-service
  uri: lb://my-xhs-content
  predicates:
    - Path=/api/content/**,/api/note/**,/api/comment/**,/api/topic/**
  metadata:
    response-timeout: 3000ms
    connect-timeout: 1000ms
    rate-limit-qps: 200
```

---

## 6. 资源配置

### 6.1 application.yml 核心配置

| 配置组 | 关键项 | 值 |
|--------|--------|------|
| Server | port | 19002 |
| Server | Tomcat threads | max=150, min-spare=15 |
| Nacos | server-addr | 21.130.247.89:18848 |
| Sentinel | dashboard | 21.130.247.89:8858 |
| Redis Sentinel | master | mymaster (26379/26380/26381) |
| Redis | cache.port | 16380（allkeys-lru） |
| Redis | business.port | 16381（noeviction） |
| MyBatis-Plus | logic-delete | deleted=0→1 |
| RocketMQ | name-server | 21.130.247.89:9876;9877 |
| RocketMQ | producer.group | content-producer-group |
| File upload | max-file-size | 5MB |
| File upload | max-request-size | 10MB |

### 6.2 读写分离

```
Master: 21.130.247.89:13307  ← 写操作
Slave:  21.130.247.89:13311  ← 读操作（@ReadOnly 自动路由）
```

### 6.3 日志配置

与 user 模块相同的 5-Appender 方案：CONSOLE / ASYNC_FILE_INFO / ASYNC_FILE_ERROR / JSON_FILE / LOGSTASH。

---

## 7. 依赖分析

### 7.1 生效依赖

| 依赖 | 用途 |
|------|------|
| `my-xhs-common` | CacheHelper, RedisOperator, IdGeneratorUtil, DFAFilter |
| `rocketmq-spring-boot-starter` | FEED_TOPIC / NOTIFICATION_TOPIC 生产者 |
| `mybatis-plus-spring-boot3-starter` | NoteMapper, CommentMapper, LocalMessageMapper |

### 7.2 依赖状态确认

| 依赖 | 状态 | 说明 |
|------|:----:|------|
| `spring-cloud-starter-openfeign` | 保留 | common 模块 `FeignUnifiedConfig` 需要（非直接使用，传递依赖） |
| `spring-cloud-starter-loadbalancer` | 保留 | common 模块 `ZoneLoadBalancerConfiguration` 需要 |
| **`my-xhs-content-api`** | **死依赖** | 模块文件系统不存在，无任何 `import com.myxhs.content.api` 引用。应移除 |
| `redisson` | 保留 | common 模块 CacheHelper / 分布式锁使用 |

### 7.3 LocalMessageMapper 注解缺失

`LocalMessageMapper` 缺少 `@Mapper` 注解（`NoteMapper` 和 `CommentMapper` 均有），目前靠 `@MapperScan("com.myxhs.content.mapper")` 兜底运行。建议补上以保持一致性。

---

## 8. 单元测试覆盖

| 测试类 | 用例数 | 测试内容 |
|--------|:------:|---------|
| `NoteServiceTest` | 4 | 正常发布、验证入库数据、查详情、查不存在的笔记抛异常 |
| `CommentServiceTest` | 4 | 发表评论、游标分页列表、缓存计数、删除 |

使用纯 Mockito Mock 模式，Mock `TransactionSynchronizationManager` 处理 `afterCommit` 回调。

---

## 9. 已知问题与改进

| 类别 | 数量 | 关键文件 |
|------|:----:|---------|
### 9.1 🔴 严重

| 问题 | 详情 |
|------|------|
| **Schema 漂移** | Flyway 迁移脚本 `V1__init_content.sql` 的 `t_local_message` 缺少 `push_status`/`push_cursor`/`push_total` 三列。对比：`mysql-content-init.sql` 有此三列 ✅，Flyway 迁移脚本 ❌。Java 代码（`LocalMessage.java`、`LocalMessageMapper` 6 个方法、`FeedMessageRetryJob` 2 个任务）已全面引用。Flyway 启动会报错 |
| **评论通知无补偿** | Note 发布有 LocalMessage + retryFailedMessages + compensateIncompletePush 三层兜底，但评论的 `NOTIFICATION_TOPIC` 仅 `afterCommit` 后 `asyncSend`，失败永久丢失 |
| **明文密码** | application.yml Redis 密码、application-datasource.properties MySQL 主/从密码均为明文 |

### 9.2 🟡 中等

| 问题 | 详情 |
|------|------|
| **死依赖** | `my-xhs-content-api` 模块不存在，无 import，应移除 |
| **LocalMessageMapper 缺 @Mapper** | 靠 @MapperScan 兜底，不一致 |
| **DFA buildTrie 无锁** | 重建 `trieRoot` 时无锁，并发 detect 可能读到半成品（TOCTOU 风险） |
| **评论 MQ 无 Trace** | CommentService 的 NOTIFICATION_TOPIC 未包装 `MqTraceHelper`，跨服务追踪断裂 |

### 9.3 🟢 提示

| 问题 | 详情 |
|------|------|
| **审核流程未实现** | `NoteStatus.AUDITING` 和 `AuditStatus.PENDING/REJECTED` 已定义，但 `publishNote()` 直接设为 PUBLISHED + APPROVED |
| **t_topic 表无映射** | SQL 定义了 t_topic 表，但无对应 Java Entity/Mapper/Service |
| **敏感词库仅 5 词** | `sensitive-words.txt` 仅 5 个测试词，注释标注"生产应扩展至 5000+"。动态词库（Redis Set + Pub/Sub）已就绪 |
| **SendCallback 与 retry 竞争** | onSuccess 的 markSent 和 retryFailedMessages 可能并发 UPDATE status=1，但均为幂等操作，无副作用 |

---

## 10. 模块文件清单

| 类别 | 数量 | 关键文件 |
|------|:----:|---------|
| 启动类 | 1 | `ContentApplication.java` |
| Controller | 2 | `NoteController` (162行), `CommentController` (120行) |
| Service | 4 | `NoteService` (566行), `CommentService` (405行), `FileStorageService` (接口), `LocalFileStorageService` (92行) |
| DTO | 6 | 3 request + 3 response |
| Entity | 3 | `Note`, `Comment`, `LocalMessage` |
| Enum | 3 | `NoteStatus`, `AuditStatus`, `NoteType` |
| Mapper | 3 | `NoteMapper`, `CommentMapper`, `LocalMessageMapper`（6 个自定义 SQL） |
| Filter | 1 | `DFAFilter` (305行) |
| Job | 1 | `FeedMessageRetryJob` (133行, 含 2 个 @Scheduled) |
| Config | 2 | `FileUploadConfig`, `RedisPubSubConfig` |
| 资源文件 | 4 | `application.yml`, `application-datasource.properties`, `logback-spring.xml`, `sensitive-words.txt` |
| SQL | 3 | `mysql-content-init.sql`, `V1__init_content.sql`, `init-replication-content.sql` |
| 测试 | 2 | `NoteServiceTest`, `CommentServiceTest` |
| Dockerfile | 1 | `Dockerfile` |
| **总计** | **36** | |## 关联文档

- `/data/workspace/my-xhs/docs/arch/02-module-interaction.md` — 模块交互拓扑
- `/data/workspace/my-xhs/docs/arch/06-cache-strategy.md` — Cache Aside + 延迟双删
- `/data/workspace/my-xhs/docs/arch/28-distributed-transaction.md` — 本地消息表 + 事务消息
- `/data/workspace/my-xhs/docs/arch/29-mq-design.md` — MQ Topic 全景
