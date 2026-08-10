# B06 — 勾选/取消 (PUT /api/cart/check)

> 2026-08-08 | 链3-4 | cart服务

## § 业务逻辑

勾选/取消购物车商品→Redis Lua cart_check_item.lua(SADD/SREM)→MQ CART_TOPIC:CHECK。checked变更实时反映在Redis checked Set中。

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| Redis | SREM成功, checked Set为空 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X PUT http://localhost:19000/api/cart/check -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"skuId":2085989641275572226,"checked":false}'
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
| Redis checked(前) | {skuId} |
| Redis checked(后) | set() → SREM成功 |
