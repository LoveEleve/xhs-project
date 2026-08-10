# CM05: 评论总数 — GET /api/comment/count/{noteId}

## § 源码分析

- **Controller**: `CommentController.java:103` → `@GetMapping("/count/{noteId}")`, 参数 `@PathVariable noteId`
- **Service**: `CommentService.getCommentCount(noteId)`
  - `SELECT COUNT(*) FROM t_comment WHERE note_id=? AND is_deleted=0`
- **权限**: 公开接口，无需登录
- **下游**: MySQL my_xhs.t_comment

## § 业务逻辑

返回笔记评论总数 → 前端展示"X条评论" → 不含is_deleted=1的记录

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| noteId有效 | 笔记存在 | 返回{count:0} |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/comment/count/1` | 200, {"count":N} |
| MySQL | `SELECT COUNT(*) FROM t_comment WHERE note_id=? AND is_deleted=0` | = count |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | note_id索引聚合 | ✅ |
| 缓存 | 可加Redis 30s | ✅ |

## § curl

```bash
curl -s "http://localhost:19012/api/comment/count/1"
```

## § ASCII流转图

```
GET /api/comment/count/{noteId}
  → CommentController.getCommentCount(noteId)
  → SELECT COUNT(*) FROM t_comment WHERE note_id=? AND is_deleted=0
  → 返回 {count: N}
```
