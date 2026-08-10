# 评论系统：楼中楼架构 + 游标分页 + N+1 优化

> **源码**: CommentController + CommentService (404 行)  
> **设计模式**: 两级嵌套 / 游标分页 / 批量预加载 / Cache-Aside
> **关键修复**: 自评通知过滤（m17）、MQ 必须在 afterCommit 内（C2）

---

## 1. 架构总览

评论系统采用**两级嵌套**结构——不支持无限级回复，只保留「一级评论 → 子评论（楼中楼）」两层：

```
笔记
 ├── 一级评论 A (parentId=0)
 │    ├── 子评论 A1 (parentId=A)
 │    ├── 子评论 A2 (parentId=A)
 │    └── 子评论 A3 (replyToId=A1)  ← 回复 A1
 ├── 一级评论 B (parentId=0)
 │    └── 子评论 B1 (parentId=B)
 └── 一级评论 C (parentId=0)
```

**为什么只支持两级？**：
1. 产品层：微信/淘宝都是两级，三级以上 UI 难以展示
2. 技术层：无限嵌套需要递归查询，Redis 缓存困难
3. 边界控制：`parentId` 层级检查在 Service 层（line 103-105），自动将孙评论的 `parentId` 修正为根评论 ID

---

## 2. 数据模型

```sql
-- t_comment 表
id          BIGINT PRIMARY KEY       -- 雪花 ID
note_id     BIGINT                   -- 所属笔记
user_id     BIGINT                   -- 评论者
parent_id   BIGINT DEFAULT 0         -- 0=一级评论，非0=子评论
reply_to_id BIGINT                   -- 被回复的评论 ID（@ 某人）
content     VARCHAR(1024)            -- 评论内容
like_count  INT DEFAULT 0            -- 点赞数
```

**核心字段**：
- `parent_id=0` → 一级评论
- `parent_id>0` → 子评论（归属某个一级评论）
- `reply_to_id` → 指定回复了谁，前端用于 "@张三 回复"

---

## 3. 发表评论：6 步校验链

```java
// CommentService.java:74-183
@Transactional(rollbackFor = Exception.class)
public Long createComment(Long userId, CommentCreateRequest request) {
    // 1. 校验笔记存在且已发布
    Note note = noteMapper.selectById(request.getNoteId());
    if (note == null || note.getStatus() != NoteStatus.PUBLISHED.getCode())
        throw new BizException(ResultCode.NOTE_NOT_FOUND);

    // 2. DFA 敏感词检测
    Set<String> sensitiveWords = dfaFilter.detect(request.getContent());
    if (!sensitiveWords.isEmpty())
        throw new BizException(ResultCode.COMMENT_CONTENT_ILLEGAL);

    // 3. 校验父评论（如果是回复）
    Long parentId = request.getParentId() != null ? request.getParentId() : 0L;
    if (parentId > 0) {
        Comment parentComment = commentMapper.selectById(parentId);
        if (parentComment == null) throw ...;
        // 确保父评论属于同一笔记
        if (!parentComment.getNoteId().equals(request.getNoteId())) throw ...;
        // 孙评论修正为根评论（只支持两级）
        if (parentComment.getParentId() > 0)
            parentId = parentComment.getParentId();
    }

    // 4. 校验被回复的评论（如果指定了 replyToId）
    if (replyToId != null && replyToId > 0) { ... }

    // 5. 入库
    Comment comment = new Comment();
    comment.setId(idGeneratorUtil.nextId());
    commentMapper.insert(comment);

    // 6. afterCommit: 清缓存 + 发通知
    TransactionSynchronizationManager.registerSynchronization(...);
}
```

### 孙评论修正机制

```
用户 C 对子评论 A1 回复 → parentId=A1（孙评论）
Service 层检测: parentComment.getParentId() > 0
    → 自动将 parentId 修正为 parentComment.getParentId() = A
    → 结果: C 变成 A 的子评论
    → replyToId 保留为 A1，前端显示 "@A1 回复"
```

**这样保证了**：
- 数据库中没有三级评论（只有 parentId=0 和 parentId=根评论ID）
- 前端 UI 不需要处理无限嵌套
- 删除逻辑简单（删一级评论 → 全量子评论删除）

---

## 4. 查询优化：游标分页 + N+1 批量规避

### 4.1 游标分页 vs OFFSET 分页

```
OFFSET 分页:  SELECT * FROM t_comment WHERE note_id=1 LIMIT 10 OFFSET 1000000
               ↑ 需要扫描 1,000,010 行，丢弃前 1,000,000 行 → 越来越慢

游标分页:     SELECT * FROM t_comment WHERE note_id=1 AND id < 987654 LIMIT 10
               ↑ id 上有索引，直接定位 → 无论第几页都是 O(logN)
```

```java
// CommentService.java:256-266
LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
        .eq(Comment::getNoteId, noteId)
        .eq(Comment::getParentId, 0);

if (lastId != null && lastId > 0) {
    wrapper.lt(Comment::getId, lastId); // 游标：只要 id < lastId 的记录
}

wrapper.orderByDesc(Comment::getId); // 最新评论在前
wrapper.last("LIMIT " + pageSize);
```

**为什么子评论用 `gt`（正序）而一级评论用 `lt`（倒序）？**

```
一级评论：按 ID 倒序 → 最新评论在前 → 用 lt (id < lastId)
子评论：  按 ID 正序 → 时间顺序展示 → 用 gt (id > lastId)
```

### 4.2 子评论批量预加载（防 N+1）

**如果不用批量预加载**（N+1 问题）：
```java
// ❌ 错误写法
for (Comment root : roots) {
    List<Comment> children = commentMapper.selectList(
        eq(Comment::getParentId, root.getId())); // N 次 SQL！
}
// 每页 10 条一级评论 → 10 次子评论查询 → 10 次 MySQL 往返
```

**批量预加载优化**（one-shot 查询）：
```java
// ✅ 正确写法：一次 SQL 查询所有子评论
List<Long> rootIds = rootComments.stream().map(Comment::getId).collect(toList());

int maxChildPerParent = DEFAULT_CHILD_PREVIEW_SIZE + 1; // 3+1=4，多查1条判断"更多"
int totalLimit = rootIds.size() * maxChildPerParent;

List<Comment> allChildren = commentMapper.selectList(
    new LambdaQueryWrapper<Comment>()
        .in(Comment::getParentId, rootIds)     // WHERE parent_id IN (A, B, C, ...)
        .orderByAsc(Comment::getId)
        .last("LIMIT " + totalLimit));          // 总量上限保护

// 按 parentId 分组
Map<Long, List<Comment>> childrenMap = allChildren.stream()
    .collect(groupingBy(Comment::getParentId));
```

**pageSize 上限保护**：
- 一级评论每页最多 20 条（`MAX_PAGE_SIZE = 20`）
- 子评论总量上限 = `rootIds.size() * 4`（最多 80 条）
- 防止恶意请求 `pageSize=1000000` 撑爆 JVM 内存

---

## 5. "查看更多" 判断逻辑

每级评论预加载 `DEFAULT_CHILD_PREVIEW_SIZE + 1 = 4` 条子评论，多出的 1 条用于判断是否还有更多：

```java
// CommentService.java:301-308
if (children.size() >= maxChildPerParent) {
    // 子评论 ≥ 4 条 → 可能还有更多 → 精确 COUNT
    long exactCount = commentMapper.selectCount(
        new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, root.getId()));
    vo.setChildCount(exactCount);
} else {
    // 子评论 < 4 条 → 就这么多 → 直接取 size，避免额外 SQL
    vo.setChildCount((long) children.size());
}
```

```
┌──────────────────────────────┐
│ 一级评论 A                   │
│ ├── 子评论 A1  ← 预加载      │
│ ├── 子评论 A2  ← 预加载      │
│ ├── 子评论 A3  ← 预加载      │
│ └── 共有 25 条回复           │
│     [查看更多回复 ▼]          │
│      ↑ children.size() ≥ 4   │
│         → exactCount = 25    │
└──────────────────────────────┘
```

---

## 6. 计数缓存：Cache-Aside + 延迟双删

```java
// CommentService.java:355-362
public long getCommentCount(Long noteId) {
    String cacheKey = RedisKeyConstants.COMMENT_COUNT + noteId;
    Long count = cacheHelper.getWithCacheAside(cacheKey, () -> {
        return commentMapper.selectCount(
            new LambdaQueryWrapper<Comment>().eq(Comment::getNoteId, noteId));
    }, 5, TimeUnit.MINUTES);
    return count != null ? count : 0L;
}
```

**为什么用缓存而不是每次 COUNT？**

每条评论的写操作都触发 `delayDoubleDelete`（见 line 146-147 和 231-232），所以缓存总是和数据库保持最终一致。5 分钟 TTL 兼顾新鲜度和性能。

---

## 7. 评论通知：排除自己评论自己

```java
// CommentService.java:149-152
// 【修复m17】排除自己评论自己的通知
if (senderId.equals(noteAuthorUserId)) {
    return;
}
```

**如果不排除会怎样？**

用户在自己笔记下发评论 → 自己收到一条通知 → 点进去发现就是自己发的。这种"自己通知自己"的行为被认为是无效通知，应该过滤。

**设计对比**：微信朋友圈的评论通知是否包含自己？是的——因为朋友圈的评论通知是给"共同好友"看的，自己评论后其他共同好友才收到通知。而小红书/知乎的笔记评论通知是给**笔记作者**一个人的——自己评论自己没有必要通知。

### 发现与修复：MQ trace 传播缺失（2026-08-04 review）

**问题**：评论通知的 `rocketMQTemplate.asyncSend` 直接发送 `Map<String, Object>`，未通过 `MqTraceHelper.wrapWithTraceContext` 包装。对比笔记发布流程中正确使用了 `wrapWithTraceContext`，导致评论通知链路在 SkyWalking 中 traceId 断裂。

**修复**（源码行 164-166）：
```java
// 修复前：直接发送 Map，无 trace 上下文
rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC", notification, callback);

// 修复后：通过 MqTraceHelper 包装，保证 notification 服务可还原 traceId
rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC",
    MqTraceHelper.wrapWithTraceContext(
        MessageBuilder.withPayload(notification).build()), callback);
```

---

## 8. 完整请求时序

```
时间 | 层级            | 操作
-----|-----------------|------
T+0  | Gateway         | JWT 解析 → X-User-Id
T+1  | @RateLimit      | 检查 10次/分钟
T+2  | @Valid          | DTO 校验
T+3  | NoteService     | 笔记存在性检查
T+4  | DFA Filter      | 敏感词检测
T+5  | @Transactional  | BEGIN
T+6  | INSERT t_comment| 评论入库
T+7  | COMMIT
T+8  | afterCommit     | 双删缓存 + MQ asyncSend(NOTIFICATION_TOPIC)
T+9  | Return 200      | 用户看到"评论成功"
```

---

## 9. 故障场景

| 场景 | 处理 |
|------|------|
| 父评论被删除，但 HTTP 请求中仍有 parentId | 校验父评论存在 → 不存在则抛异常 |
| 孙评论提交（三级回复） | Service 层自动修正 parentId 为根评论 |
| 子评论总数超过预加载限制 | children.size() ≥ 4 时执行精确 COUNT |
| DFA 检测到敏感词 | 抛异常 → 事务回滚 → afterCommit 不执行 |
| MQ 通知发送失败 | asyncSend callback 仅打日志，不影响主流程 |

---

## 10. 知识点索引

| 知识点 | 源码 | 行号 |
|--------|------|------|
| 游标分页 `lt(id, lastId)` | `CommentService.java` | 256-266 |
| 子评论 N+1 批量预加载 | `CommentService.java` | 274-288 |
| 孙评论修正为两级 | `CommentService.java` | 103-105 |
| 评论计数 Cache-Aside | `CommentService.java` | 355-362 |
| 自评通知过滤 | `CommentService.java` | 149-152 |
| afterCommit 延迟双删 | `CommentService.java` | 143-147 |
| 级联删除子评论 | `CommentService.java` | 217-224 |
| 子评论 "更多" 判断 | `CommentService.java` | 301-308 |

---

## 11. 面试要点

**Q1**: 为什么用游标分页而不是 OFFSET 分页？

**A**: OFFSET 分页在深分页（第 1000 页）时性能急剧下降，需要扫描并丢弃前 (pageNum-1)*pageSize 行。游标分页利用 `WHERE id < lastId` 直接定位到索引位置，无论翻到第几页都是 O(logN) 的索引扫描。

**Q2**: 为什么不支持三级评论（孙评论）？

**A**: 
- UI 层：手机屏幕宽度有限，三级嵌套难以展示
- 数据层：无限嵌套需要递归查询，Redis 缓存和分页逻辑复杂
- 实现：Service 层自动将孙评论的 `parentId` 修正为根评论 ID

**Q3（陷阱）**: `DEFAULT_CHILD_PREVIEW_SIZE + 1` 中 `+1` 的作用是什么？为什么不是 +0？

**A**: `+1` 用于判断"是否还有更多子评论"——如果某一级评论返回了 4 条记录，说明至少有 4 条子评论（可能更多），需要执行精确 COUNT。如果只有 3 条或更少，说明这就是全部子评论，直接用 `size()` 作为计数即可，避免无谓的 SQL 查询。

**Q4**: 如果一级评论下面有 1000 条子评论，预加载阶段会读取全部 1000 条吗？

**A**: 不会。预加载页的 `totalLimit = rootIds.size() * (DEFAULT_CHILD_PREVIEW_SIZE + 1)`，即每条一级评论最多预加载 4 条子评论。对于 10 条一级评论，总预加载上限为 40 条。点击"查看更多"时才通过 `getChildComments` 做游标加载。

---

*下一篇：DFA 敏感词过滤算法深度剖析*
