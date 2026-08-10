# NC02: 保存草稿 — POST /api/note/draft

## § 源码分析

- **Controller**: `NoteController.java:57` → `@PostMapping("/draft")`, 参数 `X-User-Id` + `@RequestBody NoteDraftDTO{title, content, images, tags, category}`
- **无RateLimit** — 草稿可频繁保存
- **Service**: `NoteService.saveDraft(userId, dto)`
  - `INSERT INTO my_xhs.t_note (user_id, title, content, images, tags, category, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', NOW())`
  - `ON DUPLICATE KEY UPDATE title=?, content=?, ...` (如果已存在草稿则更新)
- **下游**: MySQL my_xhs.t_note (仅插表, 不发MQ, 不缓存)
- **特点**: status='DRAFT' → 不可搜索/不可见public列表/不推送Feed

## § 业务逻辑

保存草稿(可反复覆盖) → INSERT/UPDATE t_note status='DRAFT' → 草稿箱可查(NC07) → 发布时NC01或NC08将DRAFT→PUBLISHED

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/note/draft` | 200 |
| MySQL | `SELECT status FROM my_xhs.t_note WHERE user_id=? AND status='DRAFT' ORDER BY created_at DESC LIMIT 1` | 存在DRAFT |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 草稿不可公开搜索 | ✅ |
| 功能 | 可重复保存覆盖 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19012/api/note/draft \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"草稿标题","content":"草稿内容","images":[],"tags":[],"category":"life"}'
```

## § ASCII流转图

```
POST /api/note/draft + X-User-Id + NoteDraftDTO
  → NoteController.saveDraft(userId, dto) [L57]
  → NoteService.saveDraft(userId, dto)
    → INSERT INTO my_xhs.t_note (status='DRAFT')
       ON DUPLICATE KEY UPDATE ... (覆盖旧草稿)
  → 返回 ok (不发MQ/不缓存)
```
