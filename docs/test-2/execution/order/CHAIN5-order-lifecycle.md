# 链5 — 订单全生命周期

> 2026-08-08 | order+inventory+payment | chaintest_c1 | orderId=2086000934497923073

## 流程

```
I01 库存初始化 (admin, sku=2085989641275572226, total=100, buckets=2)
  → D01 下单 (orderId=2086000934497923073, status=0)
    → [MQ事务消息] I02 自动预扣 Redis inventory:{sku}:total 100→98 ✅
  → M01 支付 (payType=99 mock同步)
    → [PayCallbackSimulator @Scheduled(5s)] D10 支付回调自动触发 ✅
    → [Feign] I03 库存确认扣减 ✅
  → D07 发货 (SF, tracking=SF123456)
  → D06 确认收货 (status→COMPLETED)
```

## 验证数据

| 步骤 | Redis | MQ | MySQL |
|------|------|------|------|
| I01 | inventory:{sku}:total=100 | — | t_inventory |
| D01 | total=98, prededuct key存在 | OrderTransactionConsumer "预扣减成功" ✅ | t_order(status=0) |
| M01 | — | PAY_RESULT_TOPIC | t_payment |
| D10 | prededuct key清除 | "支付成功: orderId=" ✅ | t_order.status→1 |
| D07 | — | — | t_order.status→2 |
| D06 | — | — | t_order.status→3(COMPLETED) |

## § curl

```bash
# I01 初始化
curl -s -X POST http://localhost:19000/api/inventory/init -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -d '{"skuId":2085989641275572226,"totalStock":100,"bucketCount":2}'

# D01 下单
curl -s -X POST http://localhost:19000/api/order/create -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"skuItems":[{"skuId":2085989641275572226,"quantity":2}],"addressId":2085983401418006529,"remark":"链5","bizIdentifier":"chain5-$(date +%s)"}'

# M01 支付(payType=99 mock)
curl -s -X POST http://localhost:19000/api/order/pay/create -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"orderId":2086000934497923073,"payType":99}'

# D07 发货 + D06 收货
curl -s -X POST http://localhost:19000/api/order/deliver -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"orderId":2086000934497923073,"logisticsCompany":"SF","trackingNo":"SF123456"}'
curl -s -X POST "http://localhost:19000/api/order/confirm?orderId=2086000934497923073" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085982901507301378"
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 下单→MQ事务→预扣→支付→回调→发货→收货 ✅ |
| 数据一致 | Redis库存↔MySQL订单↔MQ事务消息 三写一致 ✅ |
| 幂等安全 | bizIdentifier唯一性/SETNX防重下单 ✅ |
| 回滚完整 | ORDER_CLOSE_TOPIC延时+OrderCloseJob兜底关单 ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | order:19011+inventory:19009 actuator ✅ |
| Kibana | traceId 日志可查 ✅ |
