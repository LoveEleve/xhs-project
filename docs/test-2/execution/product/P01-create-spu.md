# P01 — 创建SPU (POST /api/product/spu)

> 2026-08-08 | 链2-1 | product服务 | chaintest_c1 | spuId=2085989545951625217

## § 业务逻辑

管理员创建SPU(商品)→MySQL INSERT t_spu(status=1上架)→afterCommit: Redis BloomFilter添加+SPU缓存清除。状态: status=1直接上架(P01后无需单独P05上架)。

## § ASCII 流转图

```
curl → Gateway:19000(JWT+X-Admin-Call)
       → my-xhs-product:19006(POST /api/product/spu)
         → SpuService.createSpu()
           → MySQL INSERT t_spu (status=1)
           → afterCommit: Redis BloomFilter add + DEL cache
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| SPU创建 | spuId=2085989545951625217 ✅ |
| MySQL | name=链2测试商品, category_id=1, status=1 ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200, X-Trace-Id: 34927d56... |
| MySQL | t_spu INSERT: id=2085989545951625217, status=1 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 安全 | X-Admin-Call 校验 ✅ |
| 限流 | @RateLimit 5/60s/user ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/product/spu -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-Admin-Call: my-xhs-admin-token-2026" \
  -d '{"name":"链2测试商品","description":"链2重测","categoryId":1,"images":[]}'
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET product:19006/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
