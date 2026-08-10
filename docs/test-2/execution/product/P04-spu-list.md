# P04 — SPU列表 (GET /api/product/spu/list)

> 2026-08-08 | 链2-6 | product服务

## § 业务逻辑

分页查询SPU→MySQL SELECT t_spu(pageNum/pageSize/categoryId可选)→@RateLimit 60/60s→返回PageResult。

## § ASCII 流转图

```
curl → Gateway:19000(JWT)
       → my-xhs-product:19006(GET /api/product/spu/list?pageNum=1&pageSize=5&categoryId=1)
         → MySQL SELECT t_spu WHERE category_id=1 LIMIT 5
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 总数 | total=11(categoryId=1的所有SPU) ✅ |
| 分页 | pageSize=5, 返回5条 ✅ |
| 含P01 | 列表包含刚创建的"链2测试商品-已更新" ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200, total=11, items=5 ✅ |
| MySQL | t_spu分页查询正确 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 性能 | @RateLimit 60/60s 防止刷接口 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/product/spu/list?pageNum=1&pageSize=5&categoryId=1" -H "Authorization: Bearer $TOKEN"
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET product:19006/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
