# NC10: 分享笔记 — POST /api/note/{id}/share

## § 源码分析

- **Controller**: `NoteController.java:154` → `@PostMapping("/{id}/share")`, 参数 `X-User-Id` + `@PathVariable Long noteId`
- **Service**: `NoteService.shareNote(noteId, userId)`
  - Redis Hash: `HINCRBY myxhs:note:counter:{noteId} share_count 1`
- **下游**: Redis Hash

## § 业务逻辑

记录分享事件 → 递增笔记分享计数 → RateLimit 10/60s

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |
| RateLimit | 10次/60s | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/note/{id}/share` | 200 |
| Redis | `r.hget('myxhs:note:counter:{noteId}','share_count')` | +1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | RateLimit 10/60s | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/note/$NOTE_ID/share" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/note/{id}/share + X-User-Id
  → NoteController.shareNote(X-User-Id, noteId)
    → HINCRBY myxhs:note:counter:{noteId} share_count 1
```
