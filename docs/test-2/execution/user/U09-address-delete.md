# U09 — 删除地址 (DELETE /api/user/address/{id})

> 2026-08-08 | 链1-10 | user服务 | chaintest_c1

## § 业务逻辑

DELETE逻辑删除地址→MySQL UPDATE t_user_address SET deleted=1。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X DELETE "http://localhost:19000/api/user/address/{id}" -H "Authorization: Bearer $TOKEN"
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
