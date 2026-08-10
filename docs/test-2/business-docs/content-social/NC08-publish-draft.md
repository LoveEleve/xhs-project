# NC08: 发布草稿 — POST /api/note/{id}/publish

## § 源码分析

- **Controller**: `NoteController.java:122` → `@PostMapping("/{id}/publish")`, 参数 `X-User-Id` + `@PathVariable Long noteId`
- **Service**: `NoteService.publishDraft(userId, noteId)`
  - `SELECT * FROM t_note WHERE id=? AND user_id=? AND status=0` → 必须是草稿
  - DFA敏感词检测
  - `UPDATE t_note SET status=1 WHERE id=?`
  - MQ: `sendMessage(FEED_TOPIC, {noteId, userId, timestamp})`
  - status!=0 → 抛异常(不能重复发布)
- **下游**: MySQL t_note + MQ FEED_TOPIC

## § 业务逻辑

验证草稿状态(status=0)→DFA→UPDATE status=1→MQ推送关注者收件箱→返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 草稿状态=0 | SELECT status=0 | 异常(已发布/已删除) |
| DFA | 敏感词检测 | 内容违规 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT status FROM t_note WHERE id=?` | 1(已发布) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 幂等 | status!=0拒绝 | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/note/$DRAFT_ID/publish" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
POST /api/note/{id}/publish
  → NoteController.publishDraft(X-User-Id, noteId)
  → SELECT status=? FROM t_note WHERE id=? AND user_id=?
    → status!=0 → 抛异常
    → status=0 → DFA检测 → UPDATE status=1
  → MQ FEED_TOPIC → home Consumer → ZADD粉丝收件箱
```
