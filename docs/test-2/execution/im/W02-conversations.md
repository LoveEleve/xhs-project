# W02 — 会话列表 (GET /api/im/conversations)

> 2026-08-08 | 链7-7 | im | chaintest_c1

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, records=0 ✅ |
| MySQL | my_xhs_im.t_chat_user_relation COUNT=0 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/im/conversations?page=1&size=20" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
