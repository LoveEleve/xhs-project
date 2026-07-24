# 评论系统：楼中楼、游标分页、通知 MQ

> `CommentService.java`（405 行）+ `CommentController.java`（120 行）
> 前置阅读：`04-transaction-aftercommit/`（afterCommit 回调）、`06-cache-strategy/`（延迟双删）、`07-mq-reliability/`（asyncSend 可靠性对比）
> 两层嵌套评论结构 + 游标分页 vs 传统分页 + 评论通知可靠性分析

---

## 1. 楼中楼设计 — 为什么只支持两层？

```sql
t_comment
  parent_id   BIGINT DEFAULT 0   -- 0=一级评论, 指向一级评论 ID=二级评论
  reply_to_id BIGINT             -- 被回复的评论 ID（楼中楼内部指向）
```

**数据结构**：

```
一级评论 (id=100, parent_id=0)
  └── 二级评论 A (id=101, parent_id=100, reply_to_id=NULL)   ← 回复一级评论
  └── 二级评论 B (id=102, parent_id=100, reply_to_id=101)    ← 回复 A
  └── 二级评论 C (id=103, parent_id=100, reply_to_id=100)    ← 也回复一级评论
```

**为什么只支持两层？**

| 更深嵌套 | 两层嵌套 |
|---------|---------|
| 需要递归查询构建树状结构 | 一次查询 + 按 parent_id 分组 |
| 前端展示复杂（缩进递归） | 前端简洁（一级展开显示子评论列表） |
| 移动端屏幕窄，三层以上无意义 | 两层已经覆盖了"A 回复 B"的所有场景 |

微信朋友圈、小红书 App 也都是两层评论——业界共识。

### 1.1 父评论自动修正

```java
// CommentService.java:90-106
if (request.getParentId() != null && request.getParentId() > 0) {
    Comment parentComment = commentMapper.selectById(request.getParentId());

    // 如果父评论本身也是子评论 → 修正 parentId 为根评论 ID
    if (parentComment.getParentId() != null && parentComment.getParentId() > 0) {
        request.setParentId(parentComment.getParentId());  // ← 关键修正
    }
}
```

前端可能传了 `parent_id=101`（二级评论 A 的 ID），后端自动修正为 `parent_id=100`（根评论 ID）。保证了所有二级评论的 `parent_id` 始终指向一级评论，数据结构简洁一致。

---

## 2. 游标分页 vs 传统分页

### 2.1 传统分页的问题

```sql
SELECT * FROM t_comment WHERE note_id=? AND parent_id=0
ORDER BY id DESC LIMIT 10 OFFSET 20;
```

当用户翻到第 3 页（OFFSET 20）时，如果第 1 页的评论之间插入了新评论（新增了 3 条），原来第 3 页的第一条会被挤到第 4 页——用户在第 3 页看到的内容翻页前和翻页后不一样。这叫**分页漂移**。

### 2.2 游标分页的解决

```sql
-- 一级评论：倒序（最新在前），用 lastId 作为游标
SELECT * FROM t_comment
WHERE note_id = ? AND parent_id = 0 AND id < #{lastId}
ORDER BY id DESC LIMIT #{pageSize}

-- 子评论：正序（按发言时间），用 lastId 作为游标
SELECT * FROM t_comment
WHERE parent_id = ? AND id > #{lastId}
ORDER BY id ASC LIMIT #{pageSize}
```

游标分页不依赖 `OFFSET`——而是记住上一页最后一条的 `id`，下一页从那里开始。即使中间插入了新评论，游标位置不变，不会出现漂移。

**为什么一级评论用倒序、二级用正序？**

| 层级 | 排序方向 | 原因 |
|:--:|:--:|------|
| 一级 | DESC（最新在前） | 用户习惯：新评论最受关注 |
| 二级 | ASC（时间线顺序） | 对话逻辑：回复按时间先后展示才有上下文 |

### 2.3 提前加载前 3 条子评论

```java
// CommentService.java:294-307
for (CommentVO root : roots) {
    List<Comment> children = commentMapper.selectList(
        eq(parentId, root.getId()).orderByAsc(id).last("LIMIT 3")
    );
    root.setChildren(toVOList(children));
    root.setChildCount(getChildrenCount(root.getId()));  // 不在 LIMIT 3 时全表 count
}
```

一级评论列表默认预加载每条评论的前 3 条子评论——用户不需要逐条点击"展开"就能看到讨论的初步内容。如果要看全部，再通过 `GET /api/comment/children/{parentId}` 接口加载更多。

**`childCount` 的查询**：`SELECT COUNT(*) FROM t_comment WHERE parent_id=?`。大部分一级评论的子评论数 ≤3——直接用 `children.size()` 得到数量，不触发额外查询。只在子评论 >3 时触发 `selectCount`，此时每条一级评论一个独立的 count 查询。若改为缓存需 per-parent 维度（Key = `comment:childcount:{parentId}`），10 条一级评论需要 10 个 key——缓存收益不大。

---

## 3. 评论通知 — 无补偿的 MQ 发送

```java
// CommentService.java afterCommit
if (!userId.equals(noteAuthorId)) {
    Map<String, Object> notification = Map.of(
        "type", 2,                    // 评论通知
        "senderId", userId,
        "targetUserId", noteAuthorId, // 笔记作者
        "targetId", noteId,
        "targetType", 1,              // 笔记
        "content", content.substring(0, Math.min(50, content.length())),
        "targetName", note.getTitle()
    );
    rocketMQTemplate.asyncSend("NOTIFICATION_TOPIC", notification, callback);
}
```

**无本地消息表保护** —— 对比笔记发布：

| 特性 | 笔记发布（FEED_TOPIC） | 评论通知（NOTIFICATION_TOPIC） |
|------|:--:|:--:|
| 本地消息表 | ✅ | ❌ |
| 定时补偿 | ✅ retryFailedMessages | ❌ |
| 死信机制 | ✅ status=3 | ❌ |
| Trace 包装 | ✅ MqTraceHelper | ❌ |
| 失败后果 | Feed 推送延迟，粉丝看不到 | 通知永久丢失 |

**设计理由**：Feed 推送是核心功能（粉丝必须看到笔记），通知是辅助功能（"有人评论了你的笔记"——丢了影响体验但不影响数据一致性）。用可靠性压缩换来代码简化。

---

## 4. 评论删除的级联效应

```java
// CommentService.java:214-224
if (comment.getParentId() == 0) {
    // 一级评论 → 级联删除所有子评论
    commentMapper.delete(
        new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, commentId)
    );
}
```

一级评论删除时，所有子评论也逻辑删除。否则子评论失去上下文——"回复 #3 楼" 但 #3 已经没了。

二级评论删除时不做级联——只删自己。

---

## 5. 总结

| 设计 | 选择 | 原因 |
|------|------|------|
| 嵌套层级 | 两层 | 业界主流，移动端适配，数据结构简单 |
| 分页方式 | 游标分页 | 无漂移，适合实时评论流 |
| 一级排序 | DESC | 用户看最新评论 |
| 二级排序 | ASC | 保持对话时间线 |
| 预加载子评论 | 前 3 条 | 减少点击展开，快速预览讨论 |
| 评论通知 | 无补偿 MQ | 辅助功能，可靠性可妥协 |
| 一级删除 | 级联 | 子评论失去上下文 |

> 通知 MQ 的可靠性对比详见 `07-mq-reliability/01-async-send-retry.md`
> 游标分页的 ID 生成详见 `08-id-generation/01-snowflake-segment.md`
> 缓存延迟双删机制详见 `06-cache-strategy/01-cache-aside-delete.md`

## 已知局限

| 局限 | 说明 |
|------|------|
| 通知 MQ 无补偿 | NOTIFICATION_TOPIC 无本地消息表保护，asyncSend 失败永久丢失 |
| 无 Trace 包装 | 评论通知的 MQ 消息未调用 `MqTraceHelper`，跨服务追踪链路断裂 |
| 一级删除无事务保证 | 级联删除子评论与一级评论的 `deleteById` 在同一事务内 ✅，但 `afterCommit` 中仅做缓存清理，无 MQ 通知 |
| childCount 全表 count | `selectCount` 在子评论很多时每次列表加载都触发，可考虑异步维护计数器 |
