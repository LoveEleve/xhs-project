# NC03: 编辑笔记 — PUT /api/note/{id}

## § 源码分析

- **Controller**: `NoteController.java:68` → `@PutMapping("/{id}")`, 参数 `X-User-Id` + `@PathVariable Long id` + `@RequestBody NoteUpdateDTO{title, content, images, tags, category}`
- **无RateLimit**
- **Service**: `NoteService.updateNote(userId, noteId, dto)`
  - `UPDATE my_xhs.t_note SET title=?, content=?, images=?, tags=?, category=?, updated_at=NOW() WHERE id=? AND user_id=? AND is_deleted=0`
  - 仅作者可改 (WHERE user_id=? 条件)
- **缓存失效**: `Redis DEL cache:note:{id}:detail` → 下次读取时重建
- **下游**: MySQL my_xhs.t_note + Redis

## § 业务逻辑

笔记作者编辑本人笔记 → UPDATE t_note(WHERE user_id=?验证所有权) → 清除旧缓存 → 返回ok → 下次详情请求从MySQL重建缓存

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| noteId存在 | `SELECT id FROM t_note WHERE id=? AND is_deleted=0` | 404 |
| 仅作者 | `WHERE user_id=?` 匹配当前用户 | 403 "无权编辑" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/note/{id}` | 200 |
| MySQL | `SELECT title FROM my_xhs.t_note WHERE id=?` | = 新title |
| Redis | `redis-cli GET cache:note:{id}:detail` | null (已失效) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | WHERE user_id=? 作者校验 | ✅ |
| 安全 | 内容安全过滤 | ✅ |
| 性能 | 缓存失效+懒重建 | ✅ |

## § curl

```bash
NOTE_ID=123456
curl -s -X PUT "http://localhost:19012/api/note/$NOTE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"修改后的标题","content":"修改后的内容","images":[],"tags":["updated"],"category":"tech"}'
```

## § ASCII流转图

```
PUT /api/note/{id} + X-User-Id + NoteUpdateDTO
  → NoteController.update(userId, noteId, dto) [L68]
  → NoteService.updateNote(userId, noteId, dto)
    → UPDATE my_xhs.t_note SET title=?, content=?, ... WHERE id=? AND user_id=? AND is_deleted=0
    → rows_affected=0 → 403
    → Redis DEL cache:note:{id}:detail (缓存失效)
  → 返回 ok
```
