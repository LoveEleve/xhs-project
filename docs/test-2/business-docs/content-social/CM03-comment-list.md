# CM03: 评论列表(游标) — GET /api/comment/list/{noteId}

## § 源码分析

- **Controller**: `CommentController.java:74` → `@GetMapping("/list/{noteId}")`, 参数 `@PathVariable noteId` + `cursor` + `size(默认20)`
- **Service**: `CommentService.getCommentList(noteId, cursor, size)`
  - 游标分页: `SELECT * FROM t_comment WHERE note_id=? AND is_deleted=0 AND id < cursor ORDER BY id DESC LIMIT ?`
  - 首次: cursor=Long.MAX_VALUE → 取最新size条
  - 下次: cursor=上一页最后一条comment.id → 防新增数据导致翻页重复
  - 不返回parent_id≠null的子评论（楼中楼独立接口CM04）
- **下游**: MySQL my_xhs.t_comment

## § 业务逻辑

游标分页加载一级评论 → id DESC倒序 → 不含子评论 → cursor分页防翻页重复

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| noteId有效 | 笔记存在 | 400 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/comment/list/1?cursor=99999999&size=20` | 200, {records:[...], hasMore} |
| MySQL | `SELECT COUNT(*) FROM t_comment WHERE note_id=? AND is_deleted=0` | = 总评论数 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | note_id索引+id<游标 | ✅ |
| 数据 | 防翻页重复 | ✅ |

## § curl

```bash
curl -s "http://localhost:19012/api/comment/list/1?cursor=99999999&size=20"
```
## § ASCII流转图

```
GET /api/comment/list/{noteId}?cursor=99999999&size=20
  → CommentController.getCommentList(noteId, cursor, size)
  → SELECT * FROM t_comment
    WHERE note_id=? AND is_deleted=0 AND parent_id IS NULL AND id < cursor
    ORDER BY id DESC LIMIT ?
  → 返回 {records, hasMore, nextCursor}
```
