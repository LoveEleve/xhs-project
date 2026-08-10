# P03 — SPU详情 (GET /api/product/spu/{spuId})

> 2026-08-08 | 链2-4 | product服务

## § 业务逻辑

Cache Aside读Redis→未命中则MySQL SELECT t_spu+t_sku→写Redis缓存30min→返回SpuDetailVO。

## § ASCII 流转图

```
curl → Gateway:19000(JWT, HMAC白名单免签名)
       → my-xhs-product:19006(GET /api/product/spu/{spuId})
         → Redis GET myxhs:product:spu:{spuId} → hit/miss
         → miss: MySQL SELECT t_spu + t_sku
         → 写Redis缓存(TTL=30min)
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| name | 链2测试商品-已更新(P02更新已生效) ✅ |
| status | 1(上架) ✅ |
| categoryId | 1 ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200, data含name/status/categoryId/skuList ✅ |
| MySQL | t_spu id=2085989545951625217 ✅ |
| Redis | SPU缓存30min(Cache Aside) ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/product/spu/2085989545951625217" -H "Authorization: Bearer $TOKEN"
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

