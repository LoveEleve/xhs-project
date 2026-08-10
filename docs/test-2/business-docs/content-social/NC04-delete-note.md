# NC04: 删除笔记 — DELETE /api/note/{id}

## § 源码分析

- **Controller**: `NoteController.java:80` → `@DeleteMapping("/{id}")`, 参数 `X-User-Id` + `@PathVariable Long id`
- **无RateLimit**
- **Service**: `NoteService.deleteNote(userId, noteId)`
  - `UPDATE my_xhs.t_note SET is_deleted=1, updated_at=NOW() WHERE id=? AND user_id=? AND is_deleted=0`
  - 软删除 → 不改物理删除, 仅标记 is_deleted=1
- **缓存失效**: `Redis DEL cache:note:{id}:detail`
- **下游**: MySQL my_xhs.t_note + Redis

## § 业务逻辑

笔记作者软删除笔记 → UPDATE is_deleted=1(不物理删除) → 缓存失效 → 删除后所有列表/搜索/详情均不可见(WHERE is_deleted=0过滤)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| noteId存在 | `SELECT id FROM t_note WHERE id=? AND is_deleted=0` | 404 |
| 仅作者 | `WHERE user_id=?` 匹配当前用户 | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/note/{id}` | 200 |
| MySQL | `SELECT is_deleted FROM my_xhs.t_note WHERE id=?` | 1 |
| NC05 | `curl /api/note/detail/{id}` | 404 (已删除) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | WHERE user_id=? 作者校验 | ✅ |
| 数据 | 软删除(可恢复) | ✅ |

## § curl

```bash
NOTE_ID=123456
curl -s -X DELETE "http://localhost:19012/api/note/$NOTE_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
DELETE /api/note/{id} + X-User-Id
  → NoteController.delete(userId, noteId) [L80]
  → NoteService.deleteNote(userId, noteId)
    → UPDATE my_xhs.t_note SET is_deleted=1 WHERE id=? AND user_id=? AND is_deleted=0
    → rows_affected=0 → 403
    → Redis DEL cache:note:{id}:detail
  → 返回 ok
```
