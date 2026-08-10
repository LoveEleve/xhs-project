# C16 — 分享笔记

`POST /api/note/{id}/share` | JWT required

## ASCII 流转图

```
[curl] → Gateway:19000 → content:19002
  → NoteController.shareNote(X-User-Id=10001, Path=/2085540601761169409/share)
  → NoteService.shareNote()
     ├ noteMapper.selectById(noteId) → 校验 status=PUBLISHED [MySQL:13307 my_xhs_content.t_note]
     ├ sendCounterEvent("SHARE") → RocketMQ 异步
     │   └ counter Consumer → Redis 更新 share 计数
     └ log "分享成功: noteId={}, userId={}"
```

## 业务逻辑

用户分享一篇已发布的笔记。service 层校验笔记存在且 status=PUBLISHED（排除草稿/删除/下线笔记），然后通过 RocketMQ 异步通知 counter 服务递增分享计数。无 MySQL 写操作，计数由 counter 服务维护。

## curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/note/2085540601761169409/share" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

## 七层验证

| 层 | 预期 | 实际 | 状态 |
|------|------|------|:--:|
| HTTP | 200 | 200 | ✅ |
| MySQL | t_note status=2(PUBLISHED) | status=2 | ✅ |
| Redis | counter key 递增 | share counter key 待确认格式 | ⚠️ |
| MQ | counter Consumer 消费 SHARE 事件 | 异步，待延迟后验证 | ⚠️ |
| 日志 | "分享成功" | content 日志不可通过 grep 获取 | ⚠️ |
| Prometheus | POST /note/{id}/share 指标 | 确认 | ✅ |
| SkyWalking | traceId | 确认 | ✅ |
