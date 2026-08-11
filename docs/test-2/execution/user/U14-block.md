# U14 — 拉黑用户 (POST /api/user/block/{targetUserId})

> 2026-08-08 | 链1-13 | user服务 | chaintest_u1→10001

## § 业务逻辑

拉黑目标用户→Redis SADD myxhs:user:block:{userId} {targetUserId}。拉黑后对方无法关注/私信。状态机: 未拉黑→SADD→已拉黑。

## § ASCII 流转图
```
curl → Gateway:19000(JWT→X-User-Id)
       → my-xhs-user:19001(POST /api/user/block/{targetUserId})
         → Redis SADD myxhs:user:block:{userId} {targetUserId}
```

## § 业务链验证
| 检查项 | 结果 |
|------|:--:|
| 拉黑成功 | 200 ✅ |
| Redis写入 | SADD 10001 ✅ |

## § 数据验证 (L2)
| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| Redis | myxhs:user:block:{userId} Set含10001 ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/user/block/10001" \
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
| Redis SADD | myxhs:user:block:{userId} ADD 10001 ✅ |
