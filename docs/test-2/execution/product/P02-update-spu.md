# P02 — 更新SPU (PUT /api/product/spu/{spuId})

> 2026-08-08 | 链2-3 | product服务

## § 业务逻辑

管理员更新SPU(name/description/categoryId)→MySQL UPDATE t_spu→afterCommit: DEL Redis SPU缓存+ES索引更新(Canal binlog)。全字段可选，不传保持原值。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-Admin-Call)
       → my-xhs-product:19006(PUT /api/product/spu/{spuId})
         → MySQL UPDATE t_spu
         → afterCommit: Redis DEL cache + Canal→ES product_index
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| name更新 | →"链2测试商品-已更新" ✅ |
| description更新 | →"全链路链2重测v2" ✅ |
| 缓存失效 | SPU缓存DEL(afterCommit) ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200 ✅ |
| MySQL | t_spu name/description已更新 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 安全 | X-Admin-Call校验 ✅ |
| 幂等 | 重复更新name=已更新→仍200 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X PUT "http://localhost:19000/api/product/spu/2085989545951625217" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -d '{"name":"链2测试商品-已更新"}'
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET product:19006/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
