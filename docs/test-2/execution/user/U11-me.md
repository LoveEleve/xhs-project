# U11 — 用户信息 (GET /api/user/me)

> 2026-08-08 | 链1-4 | user服务 | chaintest_u1

## § 业务逻辑

JWT提取userId→Cache Aside读Redis缓存→未命中查MySQL→写缓存30min。后续更新操作延迟双删缓存。

## § ASCII 流转图

```
curl → Gateway:19000 (JWT+HMAC白名单免签名)
       → my-xhs-user:19001 (GET /api/user/me)
         → UserService.getCurrentUser(X-User-Id from Gateway)
           → Redis GET myxhs:user:info:{userId} → hit
           → 返回 UserInfoResponse
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 用户信息 | id=2085982901507301378, username=chaintest_u1, status=1 ✅ |
| 缓存 | TTL=2087s(首次查询后缓存填充) ✅ |
| nickname默认 | =username(chaintest_u1) ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200, X-Trace-Id: 380a2df6... |
| Redis | myxhs:user:info:{userId} TTL=2087s(Cache Aside) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s http://localhost:19000/api/user/me -H "Authorization: Bearer $TOKEN"
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 ✅ |
| 数据一致 | 见L2 §数据验证 ✅ |
| 幂等安全 | 重复操作不产生副作用 ✅ |
| 回滚完整 | N/A(简单操作无事务) |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
