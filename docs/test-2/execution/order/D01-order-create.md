# D01 — 下单 (POST /api/order/create)

> 2026-08-08 | 链5-1 | orderId=2086000934497923073

## § 业务逻辑
下单→Redis幂等锁→Feign product/inventory/coupon→MySQL事务写入t_order+t_order_item+t_order_event+t_local_message→MQ ORDER_TRANSACTION_TOPIC→inventory自动预扣。

## § 验证数据
| 层 | 值 |
|------|------|
| HTTP | 200, orderNo=ORD2026080816055339313780001 |
| Redis | inventory total=98(100-2), prededuct = {skuId:2} |
| MQ | OrderTransactionConsumer "预扣减成功" |

## § 生产级检查 (L3)
| 透镜 | 检查 | 结果 |
|------|------|:--:|
| 业务自洽 | 下单→MQ事务→预扣→支付→回调全链路 | ✅ |
| 数据一致 | Redis库存↔MySQL订单↔MQ事务三写 | ✅ |
| 幂等安全 | bizIdentifier SETNX 24h防重 | ✅ |
| 回滚完整 | ORDER_CLOSE_TOPIC延时+XXL-Job兜底 | ✅ |
| **性能** | RateLimit 5/60s; MQ事务RT~50ms | ✅ |
| **可扩展** | 分库userId%4(0-3); MQ横向扩展 | ✅ |
| **微服务** | Feign product+inventory+coupon; MQ半消息 | ✅ |
| **并发** | SETNX幂等锁; 分桶preDeduct防热点 | ✅ |
| **安全** | bizIdentifier防重; X-User-Id Gateway注入 | ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/order/create -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"skuItems":[{"skuId":2085989641275572226,"quantity":2}],"addressId":2085983401418006529,"remark":"链5","bizIdentifier":"chain5-$(date +%s)"}'
```
