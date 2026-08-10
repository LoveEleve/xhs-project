# T09 — 测试通知发送 (POST /api/notification/test/send)

> 2026-08-08 | 链7-1 | notification | chaintest_c1 | @Profile("dev")

## § 业务逻辑

管理员发送测试通知(dev only)→MySQL INSERT t_notification→Redis INCR unread count→SSE在线推。@Profile("dev")限制生产不可用。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-Admin-Call)
       → my-xhs-notification:19013(POST /api/notification/test/send)
         → MySQL INSERT t_notification(type/sender/target/content)
         → Redis INCR myxhs:notification:unread:{userId}
         → SSE在线则推
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| MySQL | t_notification INSERT ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 性能 | 同步INSERT+Redis INCR <20ms ✅ |
| 安全 | @Profile("dev")限测试+X-Admin-Call校验 ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET notification:19013/actuator/prometheus ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/notification/test/send -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -d '{"type":1,"targetUserId":2085982901507301378,"targetId":1,"targetType":1,"content":"test"}'
```
