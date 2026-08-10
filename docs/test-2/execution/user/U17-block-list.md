# U17 — 拉黑列表 (GET /api/user/block/list)

> 2026-08-08 | 链1-15 | user服务 | chaintest_c1

## § 业务逻辑

查询当前用户的拉黑列表→Redis SMEMBERS myxhs:user:block:{userId}。

## § ASCII 流转图
```
curl → Gateway:19000(JWT→X-User-Id)
       → my-xhs-user:19001(GET /api/user/block/list)
         → Redis SMEMBERS myxhs:user:block:{userId}
```

## § 验证
| 层 | 结果 |
|------|:--:|
| HTTP | 200, data=['10001'] ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/user/block/list" \
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
| Redis SMEMBERS | myxhs:user:block:{userId} → ['10001'] ✅ |
