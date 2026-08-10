# CM06: 评论分页(备用) — GET /api/comment/page/{noteId}

## § 源码分析

- **Controller**: `CommentController.java:112` → `@GetMapping("/page/{noteId}")`, 参数 `@PathVariable Long noteId` + `pageNum/1` + `pageSize/10`
- **Service**: `CommentService.getCommentPage(noteId, pageNum, pageSize)`
  - 传统分页: `SELECT * FROM t_comment WHERE note_id=? AND parent_id IS NULL AND deleted=0`
  - ORDER BY id ASC LIMIT ?,?
  - MyBatis-Plus Page 分页
- **公开接口**: 备用接口，CM03游标分页为主

## § 业务逻辑

传统分页查询一级评论→备用接口(游标为主)→公开

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 笔记存在 | noteId有效 | 返回空 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/comment/page/1?pageNum=1&pageSize=10` | 200, PageResult |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 分页+索引 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/comment/page/1?pageNum=1&pageSize=10"
```

## § ASCII流转图

```
GET /api/comment/page/{noteId}?pageNum=1&pageSize=10
  → CommentController.getCommentPage(noteId, pageNum, pageSize)
  → SELECT * FROM t_comment WHERE note_id=? AND parent_id IS NULL LIMIT 0,10
  → 返回 PageResult<CommentVO>
```
