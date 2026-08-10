# CM01: 发表评论 — POST /api/comment

## § 源码分析

- **Controller**: `CommentController.java:38` → `@PostMapping`, `@RateLimit(key="comment:create", rate=10, window=60)` 60秒10次
- **参数**: `X-User-Id` + `@RequestBody CommentCreateDTO{noteId, content, parentId?}`
- **Service**: `CommentService.createComment(userId, dto)`
  - `INSERT INTO t_comment (note_id, user_id, content, parent_id) VALUES (?, ?, ?, ?)`
  - `RocketMQTemplate.send("SOCIAL_TOPIC", {type:"COMMENT_CREATE", commentId, noteId})`
    - `CounterEventConsumer` → `UPDATE t_note SET comment_count=comment_count+1 WHERE id=?`
- **下游**: MySQL my_xhs.t_comment + RocketMQ SOCIAL_TOPIC

## § 业务逻辑

发表评论(可含parentId楼中楼) → INSERT t_comment → MQ异步更新笔记comment_count+1 → 通知笔记作者

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| noteId有效 | 笔记存在且 is_deleted=0 | 400 "笔记不存在" |
| 内容非空 | content不为null/empty | 400 |
| 频率限制 | @RateLimit 60s/10次 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/comment` | 200, {"commentId":...} |
| MySQL | `SELECT COUNT(*) FROM t_comment WHERE note_id=? AND is_deleted=0` | 新增1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | XSS过滤content | ✅ |
| 限流 | @RateLimit 60s/10次 | ✅ |
| 异步 | MQ异步更新comment_count | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19012/api/comment \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId":1,"content":"这是一条评论"}'
```

## § ASCII流转图

```
POST /api/comment + X-User-Id + {noteId, content, parentId?}
  → CommentController.createComment(userId, dto)
  → @RateLimit check → 429 if exceeded
  → CommentService.createComment → INSERT t_comment
  → RocketMQ.send(SOCIAL_TOPIC, COMMENT_CREATE)
    → CounterEventConsumer → UPDATE t_note SET comment_count=comment_count+1
  → 返回 {commentId}
```
