# B02 — 购物车列表 (GET /api/cart/list)

> 2026-08-08 | 链3-2 | cart服务 | chaintest_c1

## § 业务逻辑

查询购物车→Redis HGETALL items + SMEMBERS checked→Feign调用product服务获取商品详情(含SKU价格/名称)→组装CartListVO。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-User-Id)
       → my-xhs-cart:19008(GET /api/cart/list)
         → Redis HGETALL myxhs:cart:{uid}:items
         → Feign GET /api/product/sku/batch?skuIds= → 商品详情
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, items=1, totalCount=1 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/cart/list" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
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
| Redis items | HGETALL myxhs:cart:{2085982901507301378}:items = {skuId:2} |
| Redis checked | SMEMBERS = {skuId} |
| MySQL t_cart_item | SELECT WHERE user=2085982901507301378 = 1 row |
