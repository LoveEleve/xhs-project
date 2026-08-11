# T06 — 按类型已读 (POST /api/notification/read-by-type/{type})

> 2026-08-08 | 链7-8 | notification | chaintest_u1

## § 业务逻辑

按通知类型批量标记已读→Redis Lua RESET_BY_TYPE(total-typeCount原子)→MySQL批量UPDATE。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| Gateway | 白名单补 read-by-type/** ✅ |

## § 踩坑

Gateway Ant模式`read/**`不匹配`read-by-type/`, 需显式加白名单。

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/notification/read-by-type/1" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```
