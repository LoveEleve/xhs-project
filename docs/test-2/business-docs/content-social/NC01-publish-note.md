# NC01: 发布笔记 — POST /api/note/publish

## § 源码分析

- **Controller**: `NoteController.java:41` → `@PostMapping("/publish")`, 参数 `X-User-Id` + `@RequestBody NotePublishDTO{title, content, images, tags, category}`
- **RateLimit**: `@RateLimit(key="note:publish", rate=5, window=60)` → 60秒内最多5次
- **Service**: `NoteService.publishNote(userId, dto)`
  - `INSERT INTO my_xhs.t_note (user_id, title, content, images, tags, category, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', NOW())`
  - `RocketMQTemplate.send("FEED_TOPIC", FeedMessage{noteId, userId, timestamp})` → 异步Feed推送
- **MQ消费者**: FeedConsumer → `INSERT INTO t_user_feed_inbox (user_id, note_id, push_time)` → 粉丝收件箱
- **缓存**: `Redis SET cache:note:{id}:detail` → 1h TTL
- **下游**: MySQL my_xhs.t_note + RocketMQ FEED_TOPIC + Redis

## § 业务逻辑

发布笔记 → INSERT t_note(status='PUBLISHED') → MQ异步发送FEED_TOPIC → CounterEventConsumer消费 → 异步更新粉丝收件箱 → 返回201+noteId

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header (Gateway注入) | 401 |
| RateLimit | 60秒内 ≤5次发布 | 429 "Too Many Requests" |
| 内容非空 | `title` 不为空, `content` 不为空 | 400 |
| 图片格式 | images数组每个URL可访问 | 400 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/note/publish` | 201, {"data":{"id":xxx}} |
| MySQL | `SELECT status,is_deleted FROM my_xhs.t_note WHERE id=?` | status='PUBLISHED', is_deleted=0 |
| Redis | `redis-cli GET cache:note:{id}:detail` | NoteDetailVO JSON |
| MQ | `redis-cli GET msg:dedup:{msgId}` (CounterEventConsumer) | not null (消费去重key) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | @RateLimit 60s/5次 | ✅ |
| 可靠 | MQ异步Feed递送+Redis去重 | ✅ |
| 性能 | INSERT单行+MQ异步推送 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19012/api/note/publish \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试笔记","content":"这是内容","images":[],"tags":["test"],"category":"tech"}' \
  | python3 -m json.tool
```

## § ASCII流转图

```
POST /api/note/publish + X-User-Id + NotePublishDTO
  → NoteController.publish(userId, dto) [L41]
  → @RateLimit 60s/5次 → 超限: 429
  → NoteService.publishNote(userId, dto)
    → INSERT my_xhs.t_note (status='PUBLISHED')
    → RocketMQ.send("FEED_TOPIC", {noteId, userId})
      → CounterEventConsumer → UPDATE t_note计数 (异步)
      → FeedConsumer → INSERT t_user_feed_inbox (粉丝收件箱)
    → Redis SET cache:note:{id}:detail 1h
  → 201 {id: noteId}
```
