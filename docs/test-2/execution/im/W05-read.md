# W05 — 标记已读 (POST /api/im/read/{peerId})

> 2026-08-08 | 链7-10 | im

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| W06复查 | total=0(标记后确认) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/im/read/10001" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
