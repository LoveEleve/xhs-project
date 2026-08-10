# U13 — 修改密码 (PUT /api/user/me/password)

> 2026-08-08 | 链1-12 | user服务 | chaintest_c1

## § 业务逻辑

旧密码校验→BCrypt加密新密码→MySQL UPDATE→revokeAllTokens(旧token全部失效,Redis DEL access+refresh)。安全设计: 密码变更后所有设备必须重新登录。

## § ASCII 流转图

```
curl → Gateway:19000(JWT)
       → my-xhs-user:19001(PUT /api/user/me/password)
         → UserService.changePassword()
           → BCrypt校验旧密码
           → MySQL UPDATE t_user.password
           → Redis DEL access + refresh token keys
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| 新密码登录 | code=200 ✅(新密码生效) |
| 旧token失效 | 401 "Token 已被注销" ✅(安全预期) |
| 还原 | 密码Test@654321→Test@123456成功 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X PUT http://localhost:19000/api/user/me/password -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"oldPassword":"Test@123456","newPassword":"Test@654321"}'
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 — 改密码+revokeTokens+新密码生效 ✅ |
| 数据一致 | 新密码BCrypt写入MySQL+旧token从Redis清除 ✅ |
| 幂等安全 | 旧密码错误→拒绝; 新密码=旧密码→拒绝 |
| 回滚完整 | N/A(原子操作，成功=改完/失败=不变) |
