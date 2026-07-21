# 评论系统

> 所属服务：my-xhs-content (9002) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

支持楼中楼评论（一级评论 + 回复评论）、评论敏感词过滤（复用笔记 DFA 过滤器）、评论举报。评论按时间序/热度序双排序。评论发表通过 RocketMQ 顺序消息保证同一笔记的评论有序。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 楼中楼评论 | ✅ | parent_id + reply_to_id 双字段，parent_id 标识直接父级，reply_to_id 用于 @某人 展示 |
| 评论敏感词过滤 | ✅ | 复用笔记发布的 DFAFilter |
| 评论举报 | ⏳ | 9 种举报类型 + 处理状态 + 满意度反馈（举报表 DDL 待补建，Phase-1 先实现基础举报接口） |
| 时间序/热度序 | ✅ | 双 ZSet 缓存，按需切换排序方式 |
| 评论顺序消息 | ✅ | RocketMQ 顺序消息，同一笔记评论进入同一 Queue |
| 评论计数联动 | ✅ | MQ 异步通知 Counter 服务更新评论数 |
| 评论点赞 | ✅ | 复用点赞服务（bizType=COMMENT） |
| 评论图片 | ❌ | 暂不支持图片评论 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 评论总量 | 5 亿 | 5000 万笔记 × 平均 10 条评论 |
| 举报总量 | 1000 万 | 评论总量的 2% |
| 日增量 | 50 万/天 | 100 万日活 × 50% 评论率 |
| 评论列表 QPS | 3000 | 笔记详情页必加载评论 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway(鉴权) → my-xhs-content(9002) → MySQL(t_comment) + Redis(评论缓存)
                              │
                              ├── DFAFilter: 评论敏感词检测（内存 Trie 树）
                              └── RocketMQ: 顺序消息 → Counter 服务更新评论数
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| my-xhs-content | MySQL | MyBatis-Plus | 评论 CRUD |
| my-xhs-content | Redis | RedisOperator | 评论列表缓存（时间序+热度序） |
| my-xhs-content | DFAFilter | 内存调用 | 评论敏感词检测 |
| my-xhs-content | RocketMQ | 顺序消息 | 评论事件 → Counter 服务 |

### 2.3 核心流程时序图

**发表评论流程：**

```
1. Client → Gateway: POST /api/comment/publish（鉴权通过）
2. Gateway → CommentService: 转发请求
3. CommentService → DFAFilter: 敏感词检测（评论内容）
4.   ├── 包含敏感词 → 返回"评论包含违规内容"
5.   └── 通过 → 继续
6. CommentService → MySQL: INSERT t_comment
7. CommentService → Redis: 清除评论列表缓存（时间序+热度序）
8. CommentService → RocketMQ: 发送顺序消息（NOTE_TOPIC:COMMENT, shardingKey=noteId）
9. Counter Consumer: 消费消息 → INCR note_comment 计数
10. CommentService → Client: 返回评论 ID
```

**楼中楼评论结构：**

```
一级评论 A（parent_id=0, root_id=0）
  ├── 回复 B（parent_id=A, root_id=A）
  │     └── 回复 C（parent_id=B, root_id=A）  ← C 回复 B，但 root 仍是 A
  └── 回复 D（parent_id=A, root_id=A）

查询逻辑：
1. 先查一级评论（parent_id=0），分页
2. 每个一级评论下查子评论（root_id=一级评论ID），按时间正序
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 评论表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_comment (
    id          BIGINT       NOT NULL COMMENT 'ID',
    note_id     BIGINT       NOT NULL COMMENT '笔记ID',
    user_id     BIGINT       NOT NULL COMMENT '评论用户ID',
    parent_id   BIGINT       DEFAULT 0 COMMENT '父评论ID(0为一级评论)',
    reply_to_id BIGINT       DEFAULT NULL COMMENT '回复的评论ID',
    content     VARCHAR(1024) NOT NULL COMMENT '评论内容',
    like_count  INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_note_id (note_id),
    INDEX idx_user_id (user_id),
    INDEX idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';
```

### 3.2 索引设计

| 索引名 | 字段 | 使用场景 |
|--------|------|----------|
| `idx_note_id` | note_id | 按笔记查评论列表（最高频） |
| `idx_user_id` | user_id | 查用户的评论历史 |
| `idx_parent_id` | parent_id | 查子评论（楼中楼） |

### 3.3 楼中楼字段设计

| 字段 | 一级评论 | 回复评论 | 说明 |
|------|---------|---------|------|
| parent_id | 0 | 被回复评论 ID | 直接父级 |
| reply_to_id | NULL | 被回复评论 ID | 用于 @某人 展示 |

> **为什么用 parent_id 而不是 root_id？** 实际 DDL 中只有 parent_id，没有 root_id。查询子评论时用 `parent_id = 一级评论ID` 即可获取该楼下所有回复。如果需要支持更深层嵌套，可后续加 root_id 字段。

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `note:comment:time:{noteId}` | ZSet | 30min | 评论时间序（score=timestamp） |
| `note:comment:hot:{noteId}` | ZSet | 30min | 评论热度序（score=likeCount） |

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 读评论列表 | Cache Aside | 先查 Redis ZSet → Miss → 查 DB → 写 Redis |
| 发表评论 | 删缓存 | DEL 时间序 + 热度序缓存，下次查询重建 |
| 删除评论 | 删缓存 | 同上 |
| 评论点赞 | 更新热度序 | ZINCRBY 更新热度序 ZSet 的 score |

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/comment/publish` | 发表评论 | ✅ |
| DELETE | `/api/comment/{id}` | 删除评论 | ✅ |
| GET | `/api/comment/list` | 评论列表（时间序） | ❌ |
| GET | `/api/comment/hot` | 评论列表（热度序） | ❌ |
| POST | `/api/comment/{id}/report` | 举报评论 | ✅ |

### 5.2 请求/响应示例

**发表评论**

```http
POST /api/comment/publish
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "noteId": 20001,
  "parentId": 0,
  "content": "这家店真的很好吃！推荐！"
}
```

```json
{
  "code": 200,
  "msg": "评论成功",
  "data": {
    "commentId": 30001
  }
}
```

**评论列表（楼中楼）**

```http
GET /api/comment/list?noteId=20001&page=1&size=10
```

```json
{
  "code": 200,
  "data": {
    "total": 128,
    "list": [
      {
        "id": 30001,
        "userId": 10086,
        "nickname": "张三",
        "avatar": "https://cdn.myxhs.com/avatar/10086.jpg",
        "content": "这家店真的很好吃！推荐！",
        "likeCount": 42,
        "createdAt": "2026-05-12 10:30:00",
        "replies": [
          {
            "id": 30002,
            "userId": 10087,
            "nickname": "李四",
            "content": "同意！我也去过，味道确实不错",
            "replyTo": "张三",
            "createdAt": "2026-05-12 10:35:00"
          }
        ],
        "replyCount": 5
      }
    ]
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 发表评论（含敏感词检测 + 顺序消息）

```java
/**
 * 发表评论
 * 关键点：敏感词检测 → 入库 → 清缓存 → 顺序消息通知计数
 */
@Override
@RateLimit(count = 10, window = 60, key = "#userId") // 自定义限流注解：每用户每 60 秒最多 10 次评论
public Long publishComment(Long userId, CommentPublishRequest request) {
    // 1. 敏感词检测（复用笔记的 DFAFilter）
    Set<String> sensitiveWords = dfaFilter.detect(request.getContent());
    if (!sensitiveWords.isEmpty()) {
        throw new BizException(BizErrorCode.CONTENT_SENSITIVE, "评论包含违规内容");
    }

    // 2. 校验笔记是否存在
    Note note = noteMapper.selectById(request.getNoteId());
    if (note == null || note.getStatus() != 2) {
        throw new BizException(BizErrorCode.NOTE_NOT_FOUND);
    }

    // 3. 构建评论实体
    Comment comment = new Comment();
    comment.setId(idGenerator.nextId());
    comment.setNoteId(request.getNoteId());
    comment.setUserId(userId);
    comment.setParentId(request.getParentId() != null ? request.getParentId() : 0L);
    comment.setContent(request.getContent());

    // 4. 入库
    commentMapper.insert(comment);

    // 5. 清除评论列表缓存
    redisOperator.delete("note:comment:time:" + request.getNoteId());
    redisOperator.delete("note:comment:hot:" + request.getNoteId());

    // 6. 发送顺序消息（同一笔记的评论进入同一 Queue）
    rocketMQTemplate.syncSendOrderly("NOTE_TOPIC:COMMENT",
            MessageBuilder.withPayload(new CommentEvent(
                    comment.getId(), request.getNoteId(), userId, "COMMENT"))
                    .build(),
            String.valueOf(request.getNoteId())); // shardingKey = noteId

    return comment.getId();
}
```

### 6.2 删除评论（含 MQ 通知计数）

```java
/**
 * 删除评论
 * 关键点：权限校验 → 逻辑删除 → 清缓存 → MQ 通知计数 DECR
 */
@Override
public void deleteComment(Long userId, Long commentId) {
    // 1. 查询评论
    Comment comment = commentMapper.selectById(commentId);
    if (comment == null || comment.getDeleted() == 1) {
        throw new BizException(BizErrorCode.COMMENT_NOT_FOUND);
    }

    // 2. 权限校验（只能删除自己的评论）
    if (!comment.getUserId().equals(userId)) {
        throw new BizException(BizErrorCode.NO_PERMISSION, "无权删除他人评论");
    }

    // 3. 逻辑删除
    comment.setDeleted(1);
    commentMapper.updateById(comment);

    // 4. 清除评论列表缓存
    redisOperator.delete("note:comment:time:" + comment.getNoteId());
    redisOperator.delete("note:comment:hot:" + comment.getNoteId());

    // 5. 发送顺序消息通知计数 DECR（与发表评论成对）
    rocketMQTemplate.syncSendOrderly("NOTE_TOPIC:COMMENT_DELETE",
            MessageBuilder.withPayload(new CommentEvent(
                    commentId, comment.getNoteId(), userId, "COMMENT_DELETE"))
                    .build(),
            String.valueOf(comment.getNoteId())); // shardingKey = noteId
}
```

### 6.3 楼中楼查询

```java
/**
 * 查询评论列表（楼中楼）
 * 1. 先查一级评论（parent_id=0），分页
 * 2. 每个一级评论下查前 3 条子评论（预览）
 * 3. 子评论总数用 replyCount 返回
 */
public PageResult<CommentVO> listComments(Long noteId, int page, int size) {
    // 1. 查一级评论（分页）
    Page<Comment> pageResult = commentMapper.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getNoteId, noteId)
                    .eq(Comment::getParentId, 0)
                    .eq(Comment::getDeleted, 0)
                    .orderByDesc(Comment::getCreatedAt));

    // 2. 批量查子评论（每个一级评论取前3条）
    List<Long> rootIds = pageResult.getRecords().stream()
            .map(Comment::getId).collect(Collectors.toList());
    Map<Long, List<Comment>> repliesMap = commentMapper.selectRepliesByRootIds(rootIds, 3);

    // 3. 批量查子评论总数
    Map<Long, Integer> replyCountMap = commentMapper.countRepliesByRootIds(rootIds);

    // 4. 组装 VO
    return buildCommentVOPage(pageResult, repliesMap, replyCountMap);
}
```

---

## ⚖️ 七、方案对比

### 7.1 评论排序：双 ZSet vs 单表多索引

| 维度 | 双 ZSet 缓存（✅ 选定） | 单表多索引 |
|------|----------------------|-----------|
| 性能 | O(logN) 分页，毫秒级 | 需要 ORDER BY + LIMIT，大偏移量慢 |
| 灵活性 | 时间序/热度序独立 ZSet | 需要不同索引 |
| 内存 | 额外 Redis 内存 | 无 |

### 7.2 评论消息：顺序消息 vs 普通消息

| 维度 | 顺序消息（✅ 选定） | 普通消息 |
|------|-------------------|---------|
| 有序性 | 同一笔记评论有序 | 无序 |
| 性能 | 略低（单 Queue 串行消费） | 高（并行消费） |
| 适用场景 | 评论计数需要严格有序 | 无序场景 |

**选择理由**：评论计数需要保证顺序（先评论后删除，不能乱序），用 noteId 作为 shardingKey 保证同一笔记的评论进入同一 Queue。

---

## 🐛 八、踩坑记录

### 8.1 楼中楼查询 N+1 问题

- **现象**：查 10 条一级评论，每条再查子评论，共 11 次 SQL
- **原因**：循环内逐条查询子评论
- **解决**：批量查询 `WHERE parent_id IN (...)` + GROUP BY，一次 SQL 查出所有子评论
- **教训**：列表查询必须避免 N+1，用批量查询替代循环查询

### 8.2 评论删除后计数不一致

- **现象**：删除评论后，笔记的评论数未减少
- **原因**：删除评论只做了逻辑删除，未发送 MQ 通知 Counter 服务
- **解决**：删除评论时发送 COMMENT_DELETE 消息，Counter 服务消费后 DECR
- **教训**：增删操作必须成对通知计数服务

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 发表一级评论 | parentId=0 | 返回评论 ID | ⬜ |
| 回复评论 | parentId=一级评论ID | 返回评论 ID | ⬜ |
| 评论含敏感词 | 含违规内容 | 返回"评论包含违规内容" | ⬜ |
| 删除自己的评论 | 自己的评论 ID | 逻辑删除成功 | ⬜ |
| 删除他人评论 | 他人评论 ID | 返回"无权限" | ⬜ |
| 时间序列表 | noteId + page | 按时间倒序返回 | ⬜ |
| 热度序列表 | noteId + page | 按点赞数倒序返回 | ⬜ |
| 举报评论 | 评论 ID + 举报类型 | 创建举报记录 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 楼中楼评论怎么设计？

**推荐回答思路**：

> 1. "用 parent_id 字段：一级评论 parent_id=0，回复评论 parent_id=被回复评论ID"
> 2. "查询分两步：先查一级评论（parent_id=0 分页），再批量查每个一级评论的子评论（WHERE parent_id IN (...)）"
> 3. "避免 N+1：不要循环查子评论，用 IN 批量查 + GROUP BY 分组"
> 4. "前端展示：一级评论下默认展示 3 条回复，点击'查看更多'加载全部"

### Q2: 为什么评论用顺序消息？

**推荐回答思路**：

> 1. "评论计数需要保证顺序：先评论（+1）后删除（-1），如果乱序变成先-1后+1，中间可能出现负数"
> 2. "用 noteId 作为 shardingKey，同一笔记的评论消息进入同一 Queue，保证 FIFO 顺序"
> 3. "代价是同一 Queue 串行消费，但评论 QPS 不高（相比点赞），可以接受"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.4 | 评论系统完整设计 |
| 📄 02-module-detailed-design.md | §4.3 | 评论楼中楼/敏感词/举报 |
