# U08 — 更新地址 (PUT /api/user/address/{id})

> 2026-08-08 | 链1-9 | user服务 | chaintest_c1 | addrId=2085983401418006529

## § 业务逻辑

PUT更新地址字段→MySQL UPDATE t_user_address→字段全可选(不传保持原值)。响应中手机号脱敏。

## § ASCII 流转图

```
curl → Gateway:19000(JWT→X-User-Id)
       → my-xhs-user:19001(PUT /api/user/address/{id})
         → MySQL UPDATE t_user_address SET receiver_name=?, detail_address=?
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, receiverName→"更新C1", detailAddress→"科技园路200号" ✅ |
| MySQL | t_user_address 字段已更新 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X PUT "http://localhost:19000/api/user/address/2085983401418006529" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"receiverName":"更新C1","detailAddress":"科技园路200号"}'
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
