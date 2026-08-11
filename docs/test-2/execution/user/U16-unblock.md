# U16 — 取消拉黑 (DELETE /api/user/block/{targetUserId})

> 2026-08-08 | 链1-14 | user服务 | chaintest_u1

## § 业务逻辑

取消拉黑→Redis SREM myxhs:user:block:{userId} {targetUserId}。

## § ASCII 流转图
```
curl → Gateway:19000(JWT→X-User-Id)
       → my-xhs-user:19001(DELETE /api/user/block/{targetUserId})
         → Redis SREM myxhs:user:block:{userId} {targetUserId}
```

## § 业务链验证
| 检查项 | 结果 |
|------|:--:|
| 取消成功 | 200 ✅ |
| Redis移除 | SREM 10001 ✅ |
| 链完整 | U14拉黑→U16取消→Set为空 ✅ |

## § 数据验证 (L2)
| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| Redis | 10001已从block Set移除 ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X DELETE "http://localhost:19000/api/user/block/10001" \
  -H "Authorization: Bearer $TOKEN"
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

## § 数据验证详情

| 层 | 实际值 |
|------|------|
| Redis SREM | myxhs:user:block:{userId} REMOVE 10001 ✅ |
