# NC05: 笔记详情 — GET /api/note/detail/{id}

## § 源码分析

- **Controller**: `NoteController.java:91` → `@GetMapping("/detail/{id}")`, 无鉴权(公开接口)
- **Service**: `NoteService.getNoteDetail(noteId)`
  - `SELECT n.*, u.nickname, u.avatar FROM my_xhs.t_note n JOIN my_xhs.t_user u ON n.user_id=u.id WHERE n.id=? AND n.is_deleted=0 AND n.status='PUBLISHED'`
  - 映射为 NoteDetailVO{id, title, content, images, tags, category, like_count, collect_count, comment_count, created_at, author{nickname, avatar}}
- **缓存**: Redis优先 `GET cache:note:{id}:detail` → 命中直接返回 → 未命中查MySQL并缓存(1h TTL)
- **下游**: MySQL my_xhs.t_note + t_user + Redis

## § 业务逻辑

公开接口 → 缓存优先查Redis → 未命中查MySQL(note+author JOIN, 仅PUBLISHED) → 写入Redis → 返回NoteDetailVO含3种计数(like/collect/comment)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 无 | 公开接口, 无需登录 | — |
| noteId存在 | WHERE is_deleted=0 AND status='PUBLISHED' | 404 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/note/detail/{id}` | 200, {data:{...}} |
| MySQL | `SELECT * FROM my_xhs.t_note WHERE id=?` | status=PUBLISHED, is_deleted=0 |
| Redis | `redis-cli TTL cache:note:{id}:detail` | 0 < TTL ≤ 3600 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 公开接口无需auth | ✅ |
| 安全 | 仅返回PUBLISHED笔记 | ✅ |
| 性能 | 缓存优先+1h TTL | ✅ |

## § curl

```bash
curl -s "http://localhost:19012/api/note/detail/123456" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/note/detail/{id} (公开, 无auth)
  → NoteController.detail(noteId) [L91]
  → Redis GET cache:note:{id}:detail
    → 命中 → 直接返回
    → 未命中 → MySQL:
      SELECT n.*, u.nickname, u.avatar
      FROM my_xhs.t_note n JOIN my_xhs.t_user u ON n.user_id=u.id
      WHERE n.id=? AND n.is_deleted=0 AND n.status='PUBLISHED'
    → Redis SET cache:note:{id}:detail 1h
  → 返回 NoteDetailVO{id, title, content, author, like_count, collect_count, comment_count}
```
