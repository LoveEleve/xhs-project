# T05 — 标记已读 (POST /api/notification/read/{id})

> 2026-08-08 | 链7-4 | notification | chaintest_c1

## § 业务逻辑

标记单条通知已读→MySQL UPDATE t_notification.is_read=1→Redis SAFE_DECR unread total+HINCRBY -1 type count。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| MySQL | t_notification.is_read=1 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
NID=$(curl -s "...api/notification/list..." | jq '.data.records[0].id')
curl -s -X POST "http://localhost:19000/api/notification/read/$NID" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
