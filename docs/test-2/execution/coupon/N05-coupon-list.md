# N05 — 用户券列表 (GET /api/coupon/user/list)

> 2026-08-08 | 链4-3 | coupon服务 | chaintest_c1

## § 业务逻辑

查询当前用户所有券→MySQL SELECT t_user_coupon WHERE user_id=?。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, coupons=1(N04领的券) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/coupon/user/list" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 ✅ |
| 数据一致 | 见L2 §数据验证 — Redis↔MySQL↔MQ 一致 ✅ |
| 幂等安全 | claim_no Docker幂等/Lua原子操作 ✅ |
| 回滚完整 | MQ失败→Lua回滚Redis库存 ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET coupon:19010/actuator/prometheus ✅ |
| Kibana | traceId 日志可查 ✅ |
