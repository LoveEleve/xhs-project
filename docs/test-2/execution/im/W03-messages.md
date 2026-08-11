# W03 — 消息列表 (GET /api/im/messages/{peerId})

> 2026-08-08 | 链7-9 | im | peerId=10001

## § 验证

| 场景 | 结果 |
|------|:--:|
| HTTP: 空(新用户) | 200, records=0 ✅ |
| HTTP: 有数据(插入1条) | 200, records=1, content="testuser→chaintest_u1: W03验证消息" ✅ |
| MySQL: my_xhs_im.t_chat_message | COUNT=1, content匹配 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/im/messages/10001?page=1&size=20" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```

## § 踩坑

t_chat_message在my_xhs_im库(13306), 非my_xhs_user。
