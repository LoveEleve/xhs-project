# T03 — 通知列表 (GET /api/notification/list)

> 2026-08-08 | 链7-2 | notification | chaintest_u1

## § 业务逻辑

分页查询当前用户通知列表→MySQL SELECT t_notification WHERE user_id=? ORDER BY created_at DESC。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, records=2(T09发的一条+历史) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/notification/list" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
