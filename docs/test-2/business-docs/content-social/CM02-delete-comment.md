# CM02: 删除评论 — DELETE /api/comment/{id}

## § 源码分析

- **Controller**: `CommentController.java:55` → `@DeleteMapping("/{id}")`, 参数 `X-User-Id` + `@PathVariable id`
- **Service**: `CommentService.deleteComment(userId, commentId)`
  - `UPDATE t_comment SET is_deleted=1 WHERE id=? AND user_id=? AND is_deleted=0`
  - `RocketMQTemplate.send("SOCIAL_TOPIC", {type:"COMMENT_DELETE", commentId, noteId})`
    - `CounterEventConsumer` → `UPDATE t_note SET comment_count=comment_count-1 WHERE id=?`
- **下游**: MySQL my_xhs.t_comment + RocketMQ SOCIAL_TOPIC

## § 业务逻辑

仅评论作者可删除 → 软删除(is_deleted=1) → MQ异步更新笔记comment_count-1

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| 评论存在 | id对应记录存在且 is_deleted=0 | 404 "评论不存在" |
| 是作者 | `user_id` = 当前用户 | 403 "无权删除" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/comment/{id}` | 200, ok |
| MySQL | `SELECT is_deleted FROM t_comment WHERE id=?` | 1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 所有者校验 | ✅ |
| 软删除 | 不物理删除 | ✅ |

## § curl

```bash
curl -s -X DELETE http://localhost:19012/api/comment/123 \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
DELETE /api/comment/{id}
  → CommentController.deleteComment(userId, id)
  → SELECT user_id FROM t_comment WHERE id=? → 不是作者:403
  → UPDATE t_comment SET is_deleted=1 WHERE id=? AND user_id=?
  → RocketMQ.send(SOCIAL_TOPIC, COMMENT_DELETE)
    → CounterEventConsumer → UPDATE t_note SET comment_count=comment_count-1
  → 返回 ok
```
