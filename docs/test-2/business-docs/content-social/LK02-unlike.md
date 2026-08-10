# LK02: 取消点赞 — DELETE /api/social/like

## § 源码分析

- **Controller**: `LikeController.java:57` → `@DeleteMapping`, 参数 `X-User-Id` + `@RequestBody LikeDTO{targetType: NOTE|COMMENT, targetId}`
- **Service**: `LikeService.unlike(userId, targetType, targetId)`
  - `DELETE FROM t_like WHERE user_id=? AND target_type=? AND target_id=?`
  - `RocketMQTemplate.send("SOCIAL_TOPIC", {event: UNLIKE_NOTE | UNLIKE_COMMENT, userId, targetId})`
  - CounterEventConsumer → `UPDATE t_note SET like_count=like_count-1 WHERE like_count>0` 或 t_comment
- **锁**: `@RateLimit(key="like:delete", rate=30, window=60)` 每分钟30次
- **幂等**: 不存在也不报错(静默DELETE)
- **下游**: MySQL t_like + MQ SOCIAL_TOPIC

## § 业务逻辑

DELETE /api/social/like → DELETE FROM t_like → MQ异步计数-1(>=0保护) → 返回ok

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` Header | 401 |
| RateLimit | 30次/分钟 | 429 |
| targetType有效 | NOTE 或 COMMENT | 400 |

*幂等: 不存在也不报错, 静默返回ok*

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/social/like` | 200 |
| MySQL | `SELECT COUNT(*) FROM t_like WHERE user_id=? AND target_type=? AND target_id=?` | 0 |
| MySQL | `SELECT like_count FROM t_note WHERE id=?` | 递减1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | user_id过滤 | ✅ |
| 幂等 | 静默DELETE不报错 | ✅ |
| 计数 | like_count>=0 | ✅ |

## § curl

```bash
curl -s -X DELETE http://localhost:19012/api/social/like \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"targetType":"NOTE","targetId":1001}'
```

## § ASCII流转图

```
DELETE /api/social/like {targetType:"NOTE", targetId:1001}
  → LikeController.unlike(userId, dto)
  → DELETE FROM t_like WHERE user_id=? AND target_type=? AND target_id=?
  → MQ SOCIAL_TOPIC → CounterEventConsumer:
    UPDATE t_note SET like_count=like_count-1 WHERE id=? AND like_count>0
  → 返回 ok
```
