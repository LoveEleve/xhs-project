# W06 — 未读计数 (GET /api/im/unread-count)

> 2026-08-08 | 链7-11 | im | chaintest_c1

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, total=0 ✅ |
| Redis | myxhs:im:unread:{userId} = {10001:0} ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/im/unread-count" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
