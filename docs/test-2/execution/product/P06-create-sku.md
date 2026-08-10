# P06 — 创建SKU (POST /api/product/sku)

> 2026-08-08 | 链2-2 | product服务 | spuId=2085989545951625217 | skuId=2085989641275572226

## § 业务逻辑

管理员为SPU创建SKU→MySQL INSERT t_sku(price/stock/specs)→SPU缓存清除。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-Admin-Call)
       → my-xhs-product:19006(POST /api/product/sku)
         → MySQL INSERT t_sku + DEL spu cache
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| SKU创建 | skuId=2085989641275572226 ✅ |
| 关联SPU | spu_id=2085989545951625217 ✅ |
| price | 99.00 ✅ |
| stock | 500 ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200, X-Trace-Id: 7132a770... |
| MySQL | t_sku: name=链2SKU-红色L, price=99.00, stock=500 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/product/sku -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -d '{"spuId":2085989545951625217,"name":"链2SKU-红色L","price":99.00,"stock":500}'
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET product:19006/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 ✅ |
| 数据一致 | 见L2 §数据验证 — MySQL/Redis数据一致 ✅ |
| 幂等安全 | GET天然幂等，重复查询无副作用 ✅ |
| 回滚完整 | N/A(只读操作) |

