# U05 — 登出 (POST /api/user/auth/logout)

> 2026-08-08 | 链1-6 | user服务 | chaintest_c1

## § 业务逻辑

JWT提取jti→DEL Redis access token→jti加入blacklist(Redis SET,TLL=剩余有效期)→refresh token可选清除。登出后旧token立即失效。

## § ASCII 流转图

```
curl → Gateway:19000 (JWT)
       → my-xhs-user:19001 (POST /api/user/auth/logout)
         → UserService.logout(accessToken, refreshToken)
           → Redis DEL myxhs:user:token:access:{userId}
           → Redis SET myxhs:user:token:blacklist:{jti} TTL=剩余有效期
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 登出成功 | code=200 ✅ |
| access token清除 | 0 remaining ✅ |
| blacklist | 12 entries ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/user/auth/logout -H "Authorization: Bearer $TOKEN"
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 — token清除+黑名单 ✅ |
| 数据一致 | 见L2 §数据验证 — Redis access DEL + blacklist SET ✅ |
| 幂等安全 | 重复logout仍200(Redis DEL/SET幂等) ✅ |
| 回滚完整 | N/A(无写操作需回滚) |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id: c644820f4938491981ad4b1e757327d4 ✅ |
| Prometheus | GET user:19001/actuator/prometheus uri=/api/user/auth/logout ✅ |
| Kibana | traceId 日志可查 ✅ |
