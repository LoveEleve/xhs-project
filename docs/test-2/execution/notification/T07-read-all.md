# T07 — 全部已读 (POST /api/notification/read-all)

> 2026-08-08 | 链7-6 | notification | chaintest_c1

## § 业务逻辑

标记所有通知已读→Redis DEL unread keys→MySQL批量UPDATE。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/notification/read-all" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
