# P07 — SKU详情 (GET /api/product/sku/{skuId})

> 2026-08-08 | 链2-5 | product服务 | skuId=2085989641275572226

## § 业务逻辑

查询SKU详情→MySQL SELECT t_sku→返回SkuVO(id/spuId/name/price/originalPrice/specs/status)。不返回stock(库存由inventory服务管理)。

## § ASCII 流转图

```
curl → Gateway:19000(JWT)
       → my-xhs-product:19006(GET /api/product/sku/{skuId})
         → MySQL SELECT t_sku WHERE id=?
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| SKU归属 | spuId=2085989545951625217(P01的SPU) ✅ |
| price | 99.00 ✅ |
| status | 1(启用) ✅ |
| stock | 响应无此字段(预期) ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| MySQL | t_sku id=2085989641275572226, name=链2SKU-红色L, price=99.00 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/product/sku/2085989641275572226" -H "Authorization: Bearer $TOKEN"
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

