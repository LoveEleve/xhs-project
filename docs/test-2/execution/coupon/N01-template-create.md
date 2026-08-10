# N01 — 创建券模板 (POST /api/coupon/template)

> 2026-08-08 | 链4-1 | coupon服务 | admin | templateId=2085998573675192321

## § 业务逻辑

管理员创建券模板→MySQL INSERT t_coupon_template(status=1)→SETNX初始化Redis库存 myxhs:coupon:{id}:stock+缓存模板JSON。type=2(折扣券)，discountValue=8折(0.1-9.9)，validStart需>当前时间(@FutureOrPresent)。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-Admin-Call)
       → my-xhs-coupon:19010(POST /api/coupon/template)
         → MySQL INSERT t_coupon_template (status=1)
         → Redis SETNX myxhs:coupon:{id}:stock = totalCount
         → Redis SET myxhs:coupon:template:{id} JSON (TTL=30min)
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| MySQL | type=2, discount=8.00, remain=50, status=1 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/coupon/template -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -d '{"name":"链4测试券","type":2,"discountValue":8.00,"minAmount":30.00,"totalCount":50,"perUserLimit":1,"validStart":"2026-08-08 16:00:00","validEnd":"2026-12-31 23:59:59"}'
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
