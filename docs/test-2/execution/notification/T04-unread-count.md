# T04 — 未读计数 (GET /api/notification/unread-count)

> 2026-08-08 | 链7-3 | notification | chaintest_c1

## § 业务逻辑

Redis GET myxhs:notification:unread:{userId} + HGETALL type count。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, total=1 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/notification/unread-count" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
