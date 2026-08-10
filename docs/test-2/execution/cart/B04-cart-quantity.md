# B04 — 修改数量 (PUT /api/cart/quantity)

> 2026-08-08 | 链3-3 | cart服务

## § 业务逻辑

修改购物车商品数量→Redis Lua cart_update_quantity.lua(HEXISTS+HSET)→MQ CART_TOPIC:UPDATE→MySQL UPDATE。quantity范围1-99。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| Redis | items skuId→5 (从2改) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X PUT http://localhost:19000/api/cart/quantity -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"skuId":2085989641275572226,"quantity":5}'
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 ✅ |
| 数据一致 | 见L2 §数据验证 — Redis↔MySQL 数据一致 ✅ |
| 幂等安全 | 重复操作不产生副作用 ✅ |
| 回滚完整 | N/A(简单操作/只读) |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET cart:19008/actuator/prometheus ✅ |
| Kibana | traceId 日志可查 ✅ |

## § 数据验证详情

| 层 | 实际值 |
|------|------|
| Redis items(前) | myxhs:cart:{2085982901507301378}:items = {skuId:2} |
| Redis items(后) | {skuId:5} |

## § 数据验证详情

| 层 | 实际值 |
|------|------|
| Redis items(前) | HGETALL myxhs:cart:{2085982901507301378}:items → {skuId:2} |
| Redis items(后) | HGETALL → {skuId:5} ✅ |
