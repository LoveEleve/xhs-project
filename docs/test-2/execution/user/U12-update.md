# U12 — 更新用户 (PUT /api/user/me)

> 2026-08-08 | 链1-5 | user服务 | chaintest_c1

## § 业务逻辑

PUT更新用户字段(nickname/signature/gender/birthday等)→MySQL UPDATE→afterCommit延迟双删Redis缓存。字段全可选，不传保持原值。

## § ASCII 流转图

```
curl → Gateway:19000 (JWT→X-User-Id)
       → my-xhs-user:19001 (PUT /api/user/me)
         → UserService.updateUser()
           → MySQL UPDATE t_user SET nickname=?,signature=?
           → afterCommit: Redis DEL myxhs:user:info:{userId} (延迟双删)
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 更新成功 | nickname→Chain1测试, signature→全链路重测v2 ✅ |
| MySQL同步 | t_user.nickname="Chain1测试" ✅ |
| 缓存失效 | TTL=-2(延迟双删) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X PUT http://localhost:19000/api/user/me -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"Chain1测试","signature":"全链路重测v2"}'
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 — MySQL写+缓存失效联动 ✅ |
| 数据一致 | 见L2 §数据验证 — MySQL更新↔Redis缓存失效 ✅ |
| 幂等安全 | 重复相同payload仍200(不产生副作用) ✅ |
| 回滚完整 | N/A(单步写操作，无事务需回滚) |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
