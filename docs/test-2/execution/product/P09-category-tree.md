# P09 — 分类树 (GET /api/product/category/tree)

> 2026-08-08 | 链2-7 | product服务

## § 业务逻辑

查询全部分类树→Redis缓存2小时→未命中则MySQL SELECT t_category→组装树形结构。

## § ASCII 流转图

```
curl → Gateway:19000(JWT)
       → my-xhs-product:19006(GET /api/product/category/tree)
         → Redis GET category tree cache(TTL=2h)
         → miss: MySQL SELECT t_category → 组装树 → 写Redis
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 分类数 | 4个顶级分类 ✅ |
| categoryId=1 | 与P01的categoryId=1对应 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/product/category/tree" -H "Authorization: Bearer $TOKEN"
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

