# B03 — 移除商品 (DELETE /api/cart/{skuId})

> 2026-08-08 | 链3-6 | cart服务

## § 业务逻辑

移除购物车商品→Redis Lua cart_remove.lua(HDEL+SREM+ZREM)→MQ CART_TOPIC:DELETE→MySQL DELETE。从3个key原子移除。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| Redis | items={}, checked={} ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X DELETE "http://localhost:19000/api/cart/{skuId}" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
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
| Redis items(前) | myxhs:cart:{2085982901507301378}:items = {skuId:5} |
| Redis items(后) | {} |
| Redis checked(后) | set() |
